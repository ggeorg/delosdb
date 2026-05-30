@echo off
setlocal
set "APP_HOME=%~dp0.."
java -cp "%APP_HOME%\lib\*" org.apache.derby.run.run %*
endlocal
