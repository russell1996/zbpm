# User task events

The engine can publish every change of a user task to RabbitMQ, so that other systems (notifications,
task boards, analytics) learn about it without polling the REST API.

## Turning it on

| Property | Default | Meaning |
|---|---|---|
| `zorrobpm.events.user-task.enabled` | `false` | Write and publish the events |
| `zorrobpm.events.user-task.poll-interval` | `1s` | Pause between publication rounds |
| `zorrobpm.events.user-task.batch-size` | `100` | Events published per batch |
| `zorrobpm.events.user-task.confirm-timeout` | `10s` | How long to wait for the broker to confirm a batch |

In Docker Compose set `ZORROBPM_EVENTS_USER_TASK_ENABLED=true`. The broker is the one of
`spring.rabbitmq.*` (`RABBITMQ_HOST`, `RABBITMQ_PORT`, ...).

The events do not depend on the transport of service task jobs (`zorrobpm.transport`). On `grpc` with the
events on, the engine connects to RabbitMQ for the events only: the job queues are neither declared nor
listened to.

Only changes made while the flag is on produce events. Events still waiting when the flag is turned off stay
in the database and are published once it is turned on again.

## Exchange and routing keys

The engine declares the durable topic exchange `zorrobpm.user-task-events` and no queues: bind your own.

| Event | Routing key | When |
|---|---|---|
| `CREATED` | `user-task.created` | A user task is created, including each task of a multi-instance |
| `ASSIGNED` | `user-task.assigned` | An open task without an assignee is claimed |
| `UNASSIGNED` | `user-task.unassigned` | The assignee of an open task is removed (unclaim) |
| `COMPLETED` | `user-task.completed` | An open task is completed |
| `CANCELED` | `user-task.canceled` | The engine ends an open task without completion: an interrupting boundary timer or BPMN error, a multi-instance ending early or getting an incident, an interrupted call activity |

A rejected operation (claiming a task taken by someone else, a completion rejected by the output mapping)
produces no event. A task assigned in the model gets `CREATED` with the assignee and no `ASSIGNED`.

Binding a queue to every event, with `rabbitmqadmin`:

```sh
rabbitmqadmin declare queue name=notifications durable=true
rabbitmqadmin declare binding source=zorrobpm.user-task-events destination=notifications routing_key='user-task.#'
```

Or only completions: `routing_key=user-task.completed`.

## Message

Each event is one persistent message with `content_type = application/json`, `message_id` equal to the
`eventId` of the event, `type` equal to the event type, `timestamp` of the change and the header
`zorrobpm-user-task-id`. The body:

```json
{
  "eventId": "6f1c2b8e-2a7d-4c5e-9d3a-0b7f2c4e1a90",
  "type": "ASSIGNED",
  "occurredAt": "2026-10-02T10:20:00Z",
  "userTaskId": "0d2f3a4b-5c6d-4e7f-8a9b-0c1d2e3f4a5b",
  "bpmnElementId": "approve",
  "name": "Approve the order",
  "formKey": "approve-form",
  "assignee": "111",
  "candidateUsers": [],
  "candidateGroups": ["managers"],
  "createdAt": "2026-10-02T10:15:30Z",
  "completedAt": null,
  "canceledAt": null,
  "loopIndex": null,
  "loopTotal": null,
  "processInstanceId": "9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d",
  "processDefinitionId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
  "processDefinitionKey": "order",
  "processDefinitionVersion": 2
}
```

- `assignee` is the assignee after the change, `null` when there is none.
- `completedAt` is set on `COMPLETED` only, `canceledAt` on `CANCELED` only.
- `loopIndex` and `loopTotal` are set on the tasks of a multi-instance.
- The fields describe the task at the moment of the change, not at the moment of publication.
- Variables are not part of the event: read them through the REST API or the tasklist by `userTaskId`.

Java consumers can read the body into `com.zorrodev.bpm.event.UserTaskLifecycleEvent` from
`zorrobpm-event`.

## Delivery guarantees

- **At least once.** An event is stored in the engine database in the transaction of the change and removed
  only after the broker confirmed its message. A command that rolls back leaves no event. If the broker is
  down, the engine keeps working and the events wait in the database until it is back, also across restarts.
  The same event may arrive more than once, always with the same `eventId`: deduplicate by it.
- **Order per task.** The events of one task are published in the order of its changes, also with several
  engine nodes and after a failed publication. There is no order between the events of different tasks.
- **Retention.** While the broker is down the waiting events are not limited; each failed round is logged as
  a warning.
