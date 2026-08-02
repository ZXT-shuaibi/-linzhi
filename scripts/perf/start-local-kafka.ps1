param(
    [string]$KafkaHome = 'D:\Tool\Kafka\apache-kafka-3.9.1',
    [string]$KafkaData = 'D:\Tool\Kafka\data\kraft-combined-logs',
    [int]$BrokerPort = 9092,
    [int]$ControllerPort = 9093,
    [switch]$StopOnly
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path "$KafkaHome\bin\windows\kafka-storage.bat")) {
    throw "Kafka installation not found: $KafkaHome"
}

$configPath = Join-Path $KafkaHome 'config\kraft\server.properties'
$runtimeConfigPath = Join-Path $KafkaHome 'config\kraft\perf-server.properties'
$java = 'D:\JDK21\bin\java.exe'
$classpath = Join-Path $KafkaHome 'libs\*'

if ($StopOnly) {
    & "$KafkaHome\bin\windows\kafka-server-stop.bat"
    exit $LASTEXITCODE
}

# Keep the performance broker data outside the project and never reuse a production log directory.
New-Item -ItemType Directory -Force -Path (Split-Path $KafkaData) | Out-Null

if (-not (Test-Path $runtimeConfigPath)) {
    $config = Get-Content -Raw $configPath
    $config = $config -replace '(?m)^listeners=.*$', "listeners=PLAINTEXT://:$BrokerPort,CONTROLLER://:$ControllerPort"
    $config = $config -replace '(?m)^advertised.listeners=.*$', "advertised.listeners=PLAINTEXT://localhost:$BrokerPort,CONTROLLER://localhost:$ControllerPort"
    $config = $config -replace '(?m)^log.dirs=.*$', "log.dirs=$($KafkaData.Replace('\', '/'))"
    $config = $config -replace '(?m)^num.partitions=.*$', 'num.partitions=6'
    Set-Content -Path $runtimeConfigPath -Value $config -Encoding UTF8
}

if (-not (Test-Path $java)) {
    throw "Java 21 is required to run the local Kafka broker: $java"
}

if (-not (Test-Path (Join-Path $KafkaData 'meta.properties'))) {
    # Format only the new local performance directory. Existing data is never deleted.
    # Kafka's Windows batch launchers overflow cmd.exe's command-line limit.
    # Java wildcard classpath is equivalent for the official binary distribution.
    $clusterId = (& $java -cp $classpath kafka.tools.StorageTool random-uuid | Select-Object -First 1).Trim()
    & $java -cp $classpath kafka.tools.StorageTool format -t $clusterId -c $runtimeConfigPath --standalone
    if ($LASTEXITCODE -ne 0) {
        throw "Kafka storage format failed with exit code $LASTEXITCODE"
    }
}

$existing = Get-NetTCPConnection -LocalPort $BrokerPort -State Listen -ErrorAction SilentlyContinue
if (-not $existing) {
    # Use cmd.exe start because this workstation exposes both PATH and Path; PowerShell
    # Start-Process rejects that case while building the child environment block.
    $startArgs = "start `"KafkaPerfBroker`" /b `"$java`" -cp `"$classpath`" kafka.Kafka `"$runtimeConfigPath`""
    & cmd.exe /c $startArgs | Out-Null

    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        $ready = Get-NetTCPConnection -LocalPort $BrokerPort -State Listen -ErrorAction SilentlyContinue
        if ($ready) {
            break
        }
    }
}

if (-not (Get-NetTCPConnection -LocalPort $BrokerPort -State Listen -ErrorAction SilentlyContinue)) {
    throw "Kafka broker did not start on port $BrokerPort"
}

# Create the counter topic used by the social interaction aggregation consumer.
& $java -cp $classpath org.apache.kafka.tools.TopicCommand --bootstrap-server "localhost:$BrokerPort" --create --if-not-exists --topic counter-events --partitions 6 --replication-factor 1
if ($LASTEXITCODE -ne 0) {
    throw "counter-events topic creation failed with exit code $LASTEXITCODE"
}

Write-Host "Kafka is ready at localhost:$BrokerPort; topic counter-events has 6 partitions."
