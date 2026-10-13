@ECHO OFF
REM Minimal Gradle launcher for Windows. Prefers a system gradle,
REM else uses a distribution unpacked under %USERPROFILE%\.gradle.
SETLOCAL
SET GRADLE_VERSION=8.7
WHERE gradle >NUL 2>&1
IF %ERRORLEVEL% EQU 0 (
  CALL gradle %*
  EXIT /B %ERRORLEVEL%
)
SET DIST=%USERPROFILE%\.gradle\wrapper\dists\gradle-%GRADLE_VERSION%-bin\gradle-%GRADLE_VERSION%\bin\gradle.bat
IF EXIST "%DIST%" (
  CALL "%DIST%" %*
  EXIT /B %ERRORLEVEL%
)
ECHO error: no system gradle and no cached Gradle %GRADLE_VERSION% distribution 1>&2
EXIT /B 1
