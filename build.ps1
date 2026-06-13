# build.ps1 — pass -NoAgent to run the app without profiling
param([switch]$NoAgent)

javac -d src\out src\HelloAgent.java
jar cfm src\profiler.jar src\manifest.txt -C src\out .
javac -d src\out src\App.java

if ($NoAgent) {
    Write-Host "=== running WITHOUT agent ===" -ForegroundColor Yellow
    java -cp src\out App
} else {
    Write-Host "=== running WITH agent ===" -ForegroundColor Green
    java -javaagent:src\profiler.jar -cp src\out App
}