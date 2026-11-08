; Windows installer for Fifteen (Inno Setup 6).
; Build the app folder first:  ./gradlew.bat -Pfifteen.public=true :composeApp:createDistributable
; Then:  ISCC.exe /DAppVersion=1.1.0 packaging\windows\fifteen.iss
; Output: composeApp\build\installer\Fifteen-Setup.exe
; (jpackage's own .exe/.msi needs WiX ICE validation, which fails on Windows builds without VBScript.)

#ifndef AppVersion
  #define AppVersion "1.1.0"
#endif
#define AppDir "..\..\composeApp\build\compose\binaries\main\app\Fifteen"

[Setup]
AppId={{6F1D2B8E-3C47-4E9A-9B15-15A7F0C2E915}
AppName=Fifteen
AppVersion={#AppVersion}
AppVerName=Fifteen {#AppVersion}
AppPublisher=Jose K James
AppPublisherURL=https://cyberianzed.github.io/Fifteen/
AppSupportURL=https://t.me/the_jkj
AppUpdatesURL=https://github.com/cyberianzed/Fifteen/releases/latest
DefaultDirName={localappdata}\Programs\Fifteen
DisableProgramGroupPage=yes
DisableDirPage=auto
PrivilegesRequired=lowest
LicenseFile=..\..\LICENSE
OutputDir=..\..\composeApp\build\installer
OutputBaseFilename=Fifteen-Setup
SetupIconFile={#AppDir}\Fifteen.ico
UninstallDisplayIcon={app}\Fifteen.exe
UninstallDisplayName=Fifteen
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=force
RestartApplications=no

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; Flags: unchecked

[InstallDelete]
; Updates replace the whole app, so old library versions never pile up.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#AppDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Fifteen"; Filename: "{app}\Fifteen.exe"
Name: "{autodesktop}\Fifteen"; Filename: "{app}\Fifteen.exe"; Tasks: desktopicon

[Registry]
; Fifteen adds itself to startup when it runs; remove that entry on uninstall.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: none; ValueName: "Fifteen"; Flags: uninsdeletevalue dontcreatekey

[Run]
Filename: "{app}\Fifteen.exe"; Description: "Start Fifteen"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/IM Fifteen.exe /F"; Flags: runhidden; RunOnceId: "StopFifteen"
