export interface MessageSubscription {
  id: string
  processInstanceId: string
  activityId: string | null
  messageName: string
  consumed: boolean
  boundaryElementId: string | null
  eventSubprocessId: string | null
  createdAt: string
}

const MOCK_SUBSCRIPTIONS: MessageSubscription[] = [
  {
    id: 'ms1',
    processInstanceId: 'pi-1',
    activityId: 'a1',
    messageName: 'payment-received',
    consumed: false,
    boundaryElementId: null,
    eventSubprocessId: null,
    createdAt: new Date().toISOString(),
  },
  {
    id: 'ms2',
    processInstanceId: 'pi-2',
    activityId: 'a2',
    messageName: 'approval-granted',
    consumed: true,
    boundaryElementId: 'boundary1',
    eventSubprocessId: null,
    createdAt: new Date(Date.now() - 3600000).toISOString(),
  },
]

export async function getMessageSubscriptions(): Promise<MessageSubscription[]> {
  return MOCK_SUBSCRIPTIONS
}
