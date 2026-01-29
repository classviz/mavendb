param (
    [Parameter(Mandatory=$true)]
    [string]$reposFolder
)
param (
    [Parameter(Mandatory=$true)]
    [string]$dbType
)

if (-not $reposFolder) {
    Write-Host "Error: repository folder is requreid"
    exit 1
}

if (-not $dbType) {
    Write-Host "Error: database type is required"
    exit 1
}

Write-Host "Mvn Repository Name to scan: $reposFolder"
Write-Host "Database Type: $dbType"

java -showversion `
 -verbose:module `
 -Xdiag `
 -Xlog:codecache,gc*,safepoint:file=../log/jvmunified.log:level,tags,time,uptime,pid:filesize=209715200,filecount=10 `
 -XshowSettings:all `
 -XX:+UnlockDiagnosticVMOptions `
 -XX:NativeMemoryTracking=summary `
 -XX:+ExtensiveErrorReports `
 -XX:+HeapDumpOnOutOfMemoryError `
 -XX:+PerfDataSaveToFile `
 -XX:+PrintClassHistogram `
 -XX:+PrintCommandLineFlags `
 -XX:+PrintConcurrentLocks `
 -XX:+PrintNMTStatistics `
 -XX:+DebugNonSafepoints `
 -XX:FlightRecorderOptions=repository=../log `
 -XX:StartFlightRecording=disk=true,dumponexit=true,filename=../log/profile.jfr,name=Profiling,settings=profile `
 -Xmx32g `
 -server `
 -jar ../mavendb.jar -f $reposFolder -d $dbType
