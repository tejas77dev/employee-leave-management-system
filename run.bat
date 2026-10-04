@echo off
setlocal
set JAVA_HOME=C:\Users\TEJAS\.jdks\jdk-21.0.12.1+1
set PATH=%JAVA_HOME%\bin;%PATH%
java -jar "%~dp0target\library-manager-1.0.0.jar"
endlocal