$env:JAVA_HOME = 'C:\Users\TEJAS\.jdks\jdk-21.0.12.1+1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

# The library manager. Listens on 8081; the leave manager in a sibling directory
# runs on 8080 and is left alone. For a restart that tracks its own process,
# use .\restart.ps1 instead of this.
Start-Process -NoNewWindow -FilePath "$env:JAVA_HOME\bin\java.exe" `
    -ArgumentList '-jar', (Join-Path $PSScriptRoot 'target\library-manager-1.0.0.jar')