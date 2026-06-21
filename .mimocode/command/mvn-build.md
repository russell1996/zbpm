---
description: "Run Maven verify for ZorroBPM: set JAVA_HOME, invoke mvn, parse BUILD/Tests output"
---

# Maven Build & Verify

Automates the repeated Maven build+test workflow for the ZorroBPM project. Handles JAVA_HOME setup, Maven invocation, and output parsing.

## Setup

This project uses:
- **JDK**: `C:\Users\1\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.10`
- **Maven**: `C:\Users\1\AppData\Local\Programs\Apache\Maven\maven-3.9.15\bin\mvn.cmd`

If these paths don't exist, fall back to:
- `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot`
- Scoop shim: `$env:USERPROFILE\scoop\shims\mvn.cmd`
- User tools: `$env:USERPROFILE\tools\apache-maven-3.9.9\bin\mvn.cmd`

Auto-detect with: `Get-Command java` and `Get-Command mvn`.

## Full Reactor Verify

```powershell
$env:JAVA_HOME = "C:\Users\1\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.10"
$mvn = "C:\Users\1\AppData\Local\Programs\Apache\Maven\maven-3.9.15\bin\mvn.cmd"
& $mvn verify *> "$env:TEMP\zbpm_verify.log"
"exit: $LASTEXITCODE"
Get-Content "$env:TEMP\zbpm_verify.log" | Select-String -Pattern "Tests run:|BUILD SUCCESS|BUILD FAILURE|Reactor Summary|zorrobpm-.*(SUCCESS|FAIL)|<<< FAIL|<<< ERROR" | Select-Object -Last 25
```

## Engine-Only Verify

```powershell
$env:JAVA_HOME = "C:\Users\1\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.10"
$mvn = "C:\Users\1\AppData\Local\Programs\Apache\Maven\maven-3.9.15\bin\mvn.cmd"
& $mvn -pl zorrobpm-engine -am verify *> "$env:TEMP\zbpm_engine_verify.log"
"exit: $LASTEXITCODE"
Get-Content "$env:TEMP\zbpm_engine_verify.log" | Select-String -Pattern "Tests run: \d+, Failures.*in com|BUILD |<<< FAIL|<<< ERROR" | Select-Object -Last 25
```

## Run Specific Integration Test

```powershell
$env:JAVA_HOME = "C:\Users\1\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.10"
$mvn = "C:\Users\1\AppData\Local\Programs\Apache\Maven\maven-3.9.15\bin\mvn.cmd"
$TEST_CLASS = "ErrorBoundaryIntegrationTests"  # replace with target class
& $mvn -pl zorrobpm-engine -am verify "-Dtest=NoSuchUnit" "-Dit.test=$TEST_CLASS" "-Dsurefire.failIfNoSpecifiedTests=false" *> "$env:TEMP\zbpm_it_test.log"
"exit: $LASTEXITCODE"
Get-Content "$env:TEMP\zbpm_it_test.log" | Select-String -Pattern "Tests run:|BUILD |<<< FAIL|<<< ERROR" | Select-Object -Last 15
```

## Engine Test-Compile Only (fast)

```powershell
$env:JAVA_HOME = "C:\Users\1\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.10"
$mvn = "C:\Users\1\AppData\Local\Programs\Apache\Maven\maven-3.9.15\bin\mvn.cmd"
& $mvn -pl zorrobpm-engine -am test-compile -q 2>&1 | Select-String -Pattern "ERROR|BUILD"
```

## Output Interpretation

- `BUILD SUCCESS` + `Tests run: N, Failures: 0, Errors: 0` = all green
- `BUILD FAILURE` or `Failures: [1-9]` = parse the `<<< FAIL` lines for failing test class + method
- `<<< ERROR` = compilation or configuration error, read the preceding lines
- `Reactor Summary` section shows per-module pass/fail at a glance
