# build.ps1 — pass -NoAgent to run the app without profiling
#             pass -Target <prefix> to filter which classes are scanned (default: App)
param(
    [switch]$NoAgent,
    [string]$Target = "App"
)

$asm = "lib\asm-9.7.1.jar"
$out = "src\out"
$jar = "src\profiler.jar"

# 1. Compile agent (with ASM on classpath)
javac -cp $asm -d $out src\HelloAgent.java

# 2. Extract ASM classes into out/ so the fat jar includes them
Push-Location $out
jar xf "..\..\$asm"
Pop-Location

# 3. Package fat jar (agent + ASM classes together)
jar cfm $jar src\manifest.txt -C $out .

# 4. Compile app
javac -d $out src\App.java

if ($NoAgent) {
    Write-Host "=== running WITHOUT agent ===" -ForegroundColor Yellow
    java -cp $out App
} else {
    Write-Host "=== running WITH agent (target: $Target) ===" -ForegroundColor Green
    java -javaagent:$jar=$Target -cp $out App
}
