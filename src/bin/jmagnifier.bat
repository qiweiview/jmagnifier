@echo off
setlocal

set "BIN_DIR=%~dp0"
for %%I in ("%BIN_DIR%..") do set "APP_HOME=%%~fI"

if not defined JMAGNIFIER_CONFIG set "JMAGNIFIER_CONFIG=%APP_HOME%\config\config.yml"

if "%~1"=="" (
  java %JAVA_OPTS% -cp "%APP_HOME%\config;%APP_HOME%\lib\*" com.AppStart "%JMAGNIFIER_CONFIG%"
) else (
  java %JAVA_OPTS% -cp "%APP_HOME%\config;%APP_HOME%\lib\*" com.AppStart %*
)

exit /b %ERRORLEVEL%
