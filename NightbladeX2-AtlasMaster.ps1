<#
==========================================================================================
  NIGHTBLADE X2  -  ATLAS OS MASTER OPTIMIZER
  Kohde : MSI Nightblade X2 | Intel Core i5-6400 (Skylake 4C/4T) | GTX 970 4GB
          16 GB RAM | Kingston A400 240GB SSD (C:) | Seagate 2TB SSD
  OS    : Windows 10 + AtlasOS
  Vaati : AJA JÄRJESTELMÄNVALVOJANA
------------------------------------------------------------------------------------------
  TÄMÄ SKRIPTI:
    1. Varmuuskopioi kaikki MUUTETTAVAT rekisteriavaimet -> %ProgramData%\NightbladeOpt
    2. Kirjoittaa RESTORE.ps1 -tiedoston, jolla KAIKKI palautetaan yhdellä komennolla
    3. Kertoo jokaisesta muutoksesta: PÄIVITETTIY / OLI VALMIS / EI ONNISTUNUT
    4. EI KOSKAAN poista Defenderia, palomuuria, UAC:ta, Windows Updatea tai BitLockeria
    5. Kaikki "kokeelliset" asetukset ovat oletuksella POIS (lippu -Experimental)
==========================================================================================
#>

#Requires -Version 5.1
[CmdletBinding()]
param(
    # Ydinytimien minimikello. 5 = moderni ja viileä (SpeedShift nostaa tarvittaessa).
    # 100 = ydin ei koskaan laske alle täyden -> ei mitään hyötyä, vain lämpöä.
    [int]$MinCpuPercent = 5,

    # Verkkokortin "Interrupt Moderation", virransäästö ja LSO pois (pienempi viive).
    [switch]$NetworkTuning,

    # Poistaa turhat taustapalvelut (ei koskaan Defender / Update / Security).
    [switch]$TrimServices,

    # Vain tarkistus: ei tee mitään muutoksia. Katso mitä tekisi.
    [switch]$AuditOnly,

    # Kokeelliset / läynnättämät asetukset PÄÄLLE. Lue kohta 9 ensin.
    [switch]$Experimental
)

$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'
Set-StrictMode -Off

# ==========================================================================================
# 0.  ALOITUS: ADMIN, LOKI, VARMUUSKOPIO
# ==========================================================================================

$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "VIRHE: Aja skripti JÄRJESTELMÄNVALVOJANA." -ForegroundColor Red
    Write-Host "  Windows-painikkeen vieressä: haku > 'powershell' > 'Suorita järjestelmänvalvojana'" -ForegroundColor DarkGray
    exit 1
}

$RootDir = Join-Path $env:ProgramData 'NightbladeOpt'
$Backup  = Join-Path $RootDir ('Backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
if (-not $AuditOnly) { New-Item -Path $Backup -ItemType Directory -Force | Out-Null }

$script:Log     = New-Object System.Collections.Generic.List[string]
$script:Backups = New-Object System.Collections.Generic.List[string]
$script:Failed  = 0
$script:Changed = 0

function Write-Log {
    param([string]$Text, [System.ConsoleColor]$Color = 'Gray')
    $script:Log.Add($Text)
    Write-Host $Text -ForegroundColor $Color
}

function Write-Section {
    param([string]$Title)
    Write-Host ''
    Write-Host ('=' * 92) -ForegroundColor DarkCyan
    Write-Host "  $Title" -ForegroundColor Cyan
    Write-Host ('=' * 92) -ForegroundColor DarkCyan
}

# PowerShell-tyypinimi -> .NET RegistryValueKind (tarvitaan koska pelkkä -ne ei erota "0" ja 0)
$KindMap = @{
    'DWord'        = [Microsoft.Win32.RegistryValueKind]::DWord
    'QWord'        = [Microsoft.Win32.RegistryValueKind]::QWord
    'String'       = [Microsoft.Win32.RegistryValueKind]::String
    'ExpandString' = [Microsoft.Win32.RegistryValueKind]::ExpandString
    'MultiString'  = [Microsoft.Win32.RegistryValueKind]::MultiString
}

function Ensure-RegKey {
    param([Parameter(Mandatory)][string]$Path)
    if (Test-Path -LiteralPath $Path) { return }
    $parts = $Path -split '\\'
    if ($parts.Count -lt 3) { return }
    $cur = ($parts[0..1] -join '\')
    foreach ($p in $parts[2..($parts.Count - 1)]) {
        if ([string]::IsNullOrWhiteSpace($p)) { continue }
        $cur = "$cur\$p"
        if (-not (Test-Path -LiteralPath $cur)) {
            New-Item -Path $cur -Force -ErrorAction SilentlyContinue | Out-Null
        }
    }
}

function Get-RegRaw {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Name)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    try { $item = Get-Item -LiteralPath $Path -ErrorAction Stop } catch { return $null }
    try {
        return [pscustomobject]@{
            Exists = $true
            Kind   = $item.GetValueKind($Name)
            Value  = $item.GetValue($Name)
        }
    } catch {
        return [pscustomobject]@{ Exists = $false; Kind = $null; Value = $null }
    }
}

function Test-SameValue {
    param($Current, $Want)
    if ($null -eq $Current) { return $false }
    try { return ([decimal]$Current.Value -eq [decimal]$Want) } catch { return $false }
}

function Backup-RegKey {
    param([string]$Path, [string]$Tag)
    if ($script:Backups -contains $Path) { return }
    if (-not (Test-Path -LiteralPath $Path)) { return }
    # Get-ChildItem palauttaa PSPath muodossa "Registry::HKLM\..." -> normalisoidaan
    # reg.exe:n ymmärtämään muotoon, muuten varmuuskopio jäisi hiljaisesti pois.
    $plain = $Path -replace '^Microsoft\.PowerShell\.Core\\Registry::', ''
    $plain = $plain -replace '^HK(LM|CU|CR|U):\\', 'HK$1\'
    $file  = Join-Path $Backup ('{0}_{1}.reg' -f ($Tag -replace '[^A-Za-z0-9]', '_'), $script:Backups.Count)
    try {
        & reg.exe export $plain $file /y 2>$null | Out-Null
        if (Test-Path -LiteralPath $file) { $script:Backups.Add($Path) | Out-Null }
    } catch { }
}

# YDIN: kirjoita vain jos arvo oikeasti eroaa, varmuista ensin ja LUKE LUETTU TULOS.
function Set-Reg {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][AllowNull()][object]$Value,
        [ValidateSet('DWord','QWord','String','ExpandString','MultiString')][string]$Type = 'DWord',
        [string]$Why = '',
        [switch]$Remove
    )

    $want = if ($Remove) { $null } else { $Value }
    $cur  = Get-RegRaw -Path $Path -Name $Name
    $has  = [bool]($cur -and $cur.Exists)

    if ($AuditOnly) {
        if ($Remove) {
            if ($has) { Write-Log "   [POISTETAAN ] $Name = $($cur.Value)   ($Why)" -Color Yellow }
            else      { Write-Log "   [OK - VALMIS ] $Name (ei poistettavaa)" -Color DarkGray }
            return
        }
        if ($has -and $cur.Kind -eq $KindMap[$Type] -and (Test-SameValue $cur $Value)) {
            Write-Log "   [OK - VALMIS ] $Name = $Value" -Color DarkGray
        } else {
            $old = if ($has) { $cur.Value } else { '(puuttui)' }
            Write-Log "   [PÄIVITETTYY] $Name : $old -> $Value   ($Why)" -Color Green
        }
        return
    }

    Ensure-RegKey -Path $Path
    Backup-RegKey -Path $Path -Tag $Name

    if ($Remove) {
        if (-not $has) { Write-Log "   [OK - VALMIS ] $Name (ei poistettavaa)" -Color DarkGray; return }
        try {
            Remove-ItemProperty -LiteralPath $Path -Name $Name -Force -ErrorAction Stop
            Write-Log "   [POISTETTU  ] $Name (oli $($cur.Value))   ($Why)" -Color Yellow
            $script:Changed++
        } catch {
            $script:Failed++
            Write-Log "   [EI ONNISTU ] $Name : $($_.Exception.Message)" -Color Red
        }
        return
    }

    if ($has -and $cur.Kind -eq $KindMap[$Type] -and (Test-SameValue $cur $Value)) {
        Write-Log "   [OK - VALMIS ] $Name = $Value" -Color DarkGray
        return
    }

    $old = if ($has) { $cur.Value } else { '(puuttui)' }
    try {
        if ($has) { Remove-ItemProperty -LiteralPath $Path -Name $Name -Force -ErrorAction SilentlyContinue }
        New-ItemProperty -LiteralPath $Path -Name $Name -Value $Value -PropertyType $Type -Force -ErrorAction Stop | Out-Null

        # LUETTELLAAN VARMISTUS. Ilman tätä "optimizerit" valehtevat onnistumisesta:
        # Get-ItemProperty toimii vaikka avain olisi kirjoituslukittu, joten virhe jääpiiloon.
        $check = Get-RegRaw -Path $Path -Name $Name
        if (-not ($check -and $check.Exists -and $check.Kind -eq $KindMap[$Type] -and (Test-SameValue $check $Value))) {
            throw 'avain lukittu / kirjoitus estetty'
        }
        $script:Changed++
        Write-Log "   [PÄIVITETTYY] $Name : $old -> $Value   ($Why)" -Color Green
    } catch {
        $script:Failed++
        Write-Log "   [EI ONNISTU ] $Name : $old -> $Value : $($_.Exception.Message)" -Color Red
    }
}

function Set-ServiceStart {
    param([string]$Name, [int]$Start, [string]$Why, [switch]$Stop)
    Set-Reg -Path "HKLM:\SYSTEM\CurrentControlSet\Services\$Name" -Name 'Start' -Value $Start -Type DWord -Why $Why
    if ($Stop -and -not $AuditOnly) {
        try {
            if ((Get-Service -Name $Name -ErrorAction Stop).Status -ne 'Stopped') {
                Stop-Service -Name $Name -Force -ErrorAction Stop
                Write-Log "   [PYSÄYTETTY ] palvelu $Name" -Color Yellow
            }
        } catch { }
    }
}

# Käyttää Windowsin "aliaksia" (esim. PROCTHROTTLEMIN) joita PowerShell/pyykit ymmärtävät.
function Set-PowerCfg {
    param(
        [Parameter(Mandatory)][string]$Sub,
        [string]$Alias,
        [string]$Guid,
        [Parameter(Mandatory)][int]$Value,
        [string]$Why = ''
    )
    $target = if ($Guid) { $Guid } else { $Alias }
    $label  = "$Sub / $target = $Value"
    try {
        if ($AuditOnly) { Write-Log "   [TULISI   ] $label   ($Why)" -Color DarkGray; return }
        & powercfg /setacvalueindex SCHEME_CURRENT $Sub $target $Value 2>$null | Out-Null
        & powercfg /setdcvalueindex SCHEME_CURRENT $Sub $target $Value 2>$null | Out-Null
        & powercfg /setactive SCHEME_CURRENT 2>$null | Out-Null
        Write-Log "   [SET      ] $label   ($Why)" -Color Green
    } catch {
        Write-Log "   [EI TUETA ] $label   ($Why)" -Color Yellow
    }
}

# ==========================================================================================
# REKISTRIPOLUT
# ==========================================================================================
$mmPath     = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Multimedia\SystemProfile'
$gamesPath  = "$mmPath\Tasks\Games"
$memPath    = 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Memory Management'
$gfxPath    = 'HKLM:\SYSTEM\CurrentControlSet\Control\GraphicsDrivers'
$prioPath   = 'HKLM:\SYSTEM\CurrentControlSet\Control\PriorityControl'
$tcpPath    = 'HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces'
$dispClass  = 'HKLM:\SYSTEM\CurrentControlSet\Control\Class\{4d36e968-e325-11ce-bfc1-08002be10318}'

Write-Host ''
Write-Host '##########################################################################' -ForegroundColor Cyan
Write-Host '#  NIGHTBLADE X2  -  ATLAS OS MASTER OPTIMIZER                            #' -ForegroundColor Cyan
Write-Host '#  i5-6400 / GTX 970 / 16GB / SSD                                       #' -ForegroundColor Cyan
Write-Host '##########################################################################' -ForegroundColor Cyan
Write-Log "Varmuuskopiohakemisto : $Backup"
Write-Log "VARSINAINEN TILA      : $($AuditOnly.IsPresent)"
Write-Log "Kokeelliset asetukset : $($Experimental.IsPresent)"

# ==========================================================================================
# 1.  LAITERAPORTIT (vain lukee)
# ==========================================================================================
Write-Section '1. LAITERAPORTIT'

try {
    $cpu = Get-CimInstance Win32_Processor -ErrorAction Stop | Select-Object -First 1
    Write-Log ('   CPU     : {0}  ({1}C/{2}T, nyky {3} GHz / max {4} GHz)' -f `
        $cpu.Name.Trim(), $cpu.NumberOfCores, $cpu.NumberOfLogicalProcessors,
        [math]::Round($cpu.CurrentClockSpeed / 1000, 2), [math]::Round($cpu.MaxClockSpeed / 1000, 2)) -Color White
} catch { Write-Log '   CPU     : ei luettavissa' -Color DarkGray }

try {
    foreach ($g in @(Get-CimInstance Win32_VideoController -ErrorAction Stop)) {
        Write-Log ('   GPU     : {0}  ({1} MB, ajuri {2})' -f $g.Name.Trim(), $g.AdapterRAM, $g.DriverVersion) -Color White
    }
} catch { }

try {
    $ram = @(Get-CimInstance Win32_PhysicalMemory -ErrorAction Stop)
    $totalGB = [math]::Round((($ram | Measure-Object -Property Capacity -Sum).Sum / 1GB), 1)
    Write-Log ('   RAM     : {0} GB, {1} moduulia ({2})' -f $totalGB, $ram.Count,
        (($ram | ForEach-Object { "$($_.DeviceLocator):$([math]::Round($_.Capacity/1GB,1))GB" }) -join ', ')) -Color White
    if ($ram.Count -eq 1) {
        Write-Log '   ==> VAIN YKSI RAM-MODUULI = SINGLE CHANNEL. Yksi isoimmista FPS-vesistä!' -Color Yellow
        Write-Log '       Toinen saman kokoinen moduuli tuottaa yleensä +15-30 % FPS:iä täysin ilman kustannuksia.' -Color DarkGray
    } elseif ((($ram | ForEach-Object { [decimal]$_.Capacity } | Sort-Object -Unique).Count) -eq 1) {
        Write-Log '   ==> RAM on symmetrinen. Varmista että se on DUAL CHANNEL -tilassa BIOSissa.' -Color Green
    } else {
        Write-Log '   ==> RAM EPÄSYMETRINEN: CPU toimii single-channelilla. Tämä kannattaa korjata ensimmäisenä.' -Color Yellow
    }
} catch { }

try {
    foreach ($d in @(Get-PhysicalDisk -ErrorAction SilentlyContinue)) {
        Write-Log ('   VARASTO : {0}  ({1}, {2} GB)' -f $d.FriendlyName, $d.MediaType, [math]::Round($d.Size / 1GB)) -Color DarkGray
    }
} catch { }

Write-Log ('   VIRTA   : ' + ((powercfg /getactivescheme) -join ' ')) -Color DarkGray

# ==========================================================================================
# 2.  VIRRANHALLINTA  <-- tästä on oikeasti mitattava hyöty
# ==========================================================================================
Write-Section '2. VIRRANHALLINTA JA YDINPAKOFFI'

if ($AuditOnly) {
    Write-Log '   [TULISI  ] virransuunnitelma = Ultimate Performance' -Color DarkGray
} else {
    $up   = 'e9a42b02-d5df-448d-aa00-03f14749eb61'
    $list = (powercfg /list) -join "`n"
    if ($list -notmatch [regex]::Escape($up)) {
        Write-Log '   Ultimate Performance puuttuu -> kopioidaan High Performance' -Color Yellow
        & powercfg /duplicatescheme 8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c 2>$null | Out-Null
    }
    try {
        & powercfg /setactive $up | Out-Null
        Write-Log '   [SET     ] virransuunnitelma = Ultimate Performance' -Color Green
    } catch {
        Write-Log '   [EI ONNISTU] virransuunnitelman vaihto' -Color Red
    }
}

# Ultimate Performance poistaa jokaisen virransäästöviiveen ja CPU-throttelun.
Set-PowerCfg -Sub SUB_PROCESSOR -Alias PROCTHROTTLEMIN -Value ([math]::Max(1, $MinCpuPercent)) `
    -Why "Alin kellotaajuus $MinCpuPercent%. SpeedShift nostaa heti tarvittaessa."
Set-PowerCfg -Sub SUB_PROCESSOR -Alias PROCTHROTTLEMAX -Value 100 `
    -Why 'Ydin ei rajoitu koskaan alle 100%:n. Poistaa Windowsin turhan hillinnän.'
Set-PowerCfg -Sub SUB_PROCESSOR -Guid 'be337238-0d82-4146-a960-4f3749d470c7' -Value 2 `
    -Why 'Boost-tila = Aggressive: turbo kestää pidempään ennen laskua.'
Set-PowerCfg -Sub SUB_PROCESSOR -Alias PERFEPP -Value 0 `
    -Why 'Energy Efficiency Preference 0 = suurin suorituskyky. Oletus 128 = Windows "suosittelee" energiasäästöä.'
Set-PowerCfg -Sub SUB_PROCESSOR -Alias CPMINCORES -Value 100 `
    -Why 'CORE UNPARKING: kaikki 4 ydintä päällä. Oletus jättää 3 ydintä jatkuvasti idleen.'
Set-PowerCfg -Sub SUB_PROCESSOR -Alias PROCTHROTTLE -Value 1 `
    -Why 'Estää Windowsin pudottamasta yli 20 % kellotaajuutta (huono workaround, mutta haittaa).'
Set-PowerCfg -Sub SUB_PROCESSOR -Alias PERFBOOSTPOL -Value 100 `
    -Why 'Boost sallitaan myös akkuvirralla / heikolla virtalähteellä.'
Set-PowerCfg -Sub SUB_PROCESSOR -Alias PERFINCTHRESHOLD -Value 10 `
    -Why 'P-state nousee jo 10 % kuormalla (pienempi idle->käyttöönoton viive).'
Set-PowerCfg -Sub SUB_PROCESSOR -Alias PERFDECTHRESHOLD -Value 8 `
    -Why 'P-state laskee vasta 8 % kuormalla.'
Set-PowerCfg -Sub SUB_PROCESSOR -Alias PERFINCPOL -Value 2 -Why 'Aggressiivinen P-state-ylösnousu.'
Set-PowerCfg -Sub SUB_PROCESSOR -Alias PERFDECPOL -Value 1 -Why 'Pudotetaan P-state vain tarvittaessa.'

Set-PowerCfg -Sub SUB_PCIEXPRESS -Alias ASPM -Value 0 `
    -Why 'PCIe-linkin virransäästö pois: ei nukahtamisviivettä kun GPU/levy herää. Kuluttaa ~5-15 W enemmän.'
Set-PowerCfg -Sub SUB_DISK -Alias DISKIDLE -Value 0 `
    -Why 'Levy ei koskaan mene lepotilaan (vähittäiset pääsyn kestot).'
Set-PowerCfg -Sub SUB_USB -Alias USBSELECTIVE -Value 0 `
    -Why 'USB-porttien automaattinen sammutus pois (välkkyvä hiiri, headsetit).'

# ==========================================================================================
# 3.  MMCSS, AJOITUS, REKISTERIN KESKITTYMINEN
# ==========================================================================================
Write-Section '3. MMCSS (MULTIMEDIA-AIKATAULU) JA PROSESSORIAJOITUS'

# TÄMÄ ON YKSI OIKEISTA WIN10-PELIKORJAAKSISTA. Windows normaalisti EI KOSKAAN käytä
# 100 % CPU:ta: se jättää aina ~20 % varaa taustatyölle (Defender, Update, AtlasOS).
# Peli ei siis koskaan saa koko prosessoria, vaikka 3 ydintä olisi tyhjänä.
Set-Reg -Path $mmPath -Name 'SystemResponsiveness' -Value 0 -Type DWord `
    -Why 'EI 20 % CPU-varausta. Suurin rekisteripohjainen yksittäinen korjaus Win10:llä.'

# 0xFFFFFFFF on Microsoftin dokumentoima "throttle pois" -arvo.
Set-Reg -Path $mmPath -Name 'NetworkThrottlingIndex' -Value ([uint32]::MaxValue) -Type DWord `
    -Why 'Multimedian verkkothrottle pois. Oletus hidastaa MMCSS-tehtävää jopa 10x.'

# MMCSS-tehtävä on rekisteröity 'Games'-nimellä, muuten yllä olevat arvot eivät käytössä.
Set-Reg -Path $mmPath -Name 'Games' -Value 'Games' -Type String `
    -Why 'Rekisteröi Games-tehtävä MMCSS:lle. Ilman tätä sen asetukset ovat koristeita.'

# Annaa pelille oman korkean QoS-prioriteetin.
Set-Reg -Path $gamesPath -Name 'SFIO Priority'      -Value 'High' -Type String -Why 'Pelille korkea QoS-prioriteetti.'
Set-Reg -Path $gamesPath -Name 'Priority'           -Value 6       -Type DWord  -Why 'MMCSS-prioriteettiluokka 6 (erittäin korkea).'
Set-Reg -Path $gamesPath -Name 'GPU Priority'       -Value 8       -Type DWord  -Why 'MMCSS GPU-prioriteetti maksimiin: GPU:ta ohjataan ensin.'
Set-Reg -Path $gamesPath -Name 'Scheduling Category' -Value 'High'  -Type String -Why 'Sama koskee ajastusluokkaa.'

# Windowsin oletusarvo. EI MUUTA MITÄÄN, mutta varmistetaan ettei joku ole kääntänyt sitä.
Set-Reg -Path $prioPath -Name 'Win32PrioritySeparation' -Value 38 -Type DWord `
    -Why 'Säilyttää oletuksen (2/38 kvanttia). Käytännössä kosmeettinen asetus.'

# Windows Update hoitaa Spectre/Meltdown-lievitykset oikein. Tämä manuaalinen pakko-ohitus
# ei paranna suorituskykyä lainkaan, mutta estää tulevien päivitysten automaattisen korjauksen.
Set-Reg -Path $memPath -Name 'FeatureSettingsOverride'     -Value $null -Type DWord -Remove `
    -Why 'Poistaa turhan Spectre-ohituksen. Suorituskykyyn ei vaikutusta.'
Set-Reg -Path $memPath -Name 'FeatureSettingsOverrideMask' -Value $null -Type DWord -Remove `
    -Why 'Sama koskee maskia: riskitön, hyöditön, helposti unohdettu ohitus.'

# ==========================================================================================
# 4.  MUISTI, SSD, KÄYNNISTYS
# ==========================================================================================
Write-Section '4. MUISTINHALLINTA, SSD JA KÄYNNISTYS'

# SSD:llä jokainen "viimeksi avattu" -aikaleiman päivitys aiheuttaa turhan kirjoituksen.
Set-Reg -Path $memPath -Name 'NtfsDisableLastAccessUpdate' -Value 1 -Type DWord `
    -Why 'Ei päivitä atime-aijoinformaatiota -> vähemmän SSD-kirjoitusta ja lyhyempi I/O-viive.'

Set-Reg -Path "$memPath\PrefetchSettings" -Name 'EnablePrefetcher'  -Value 3 -Type DWord `
    -Why 'Prefetch = muistissa + levyiltä. Oikea arvo SSD:llä (Windows asettaa itsensä 0 jos levy on SSD).'
Set-Reg -Path "$memPath\PrefetchSettings" -Name 'EnableSuperfetch'   -Value 0 -Type DWord `
    -Why 'Superfetch on kiintolevyn tekniikka. SSD:llä vain resurssien tuhlaa.'
Set-Reg -Path "$memPath\PrefetchSettings" -Name 'EnableSourcePaging' -Value 0 -Type DWord `
    -Why 'Paging-tiedostojen esilukeminen pois.'
Set-Reg -Path $memPath -Name 'ClearPageFileAtShutdown' -Value 0 -Type DWord `
    -Why 'Sammutuksen turha levynkirjoitus pois.'

# Page tiedostoa EI poisteta: 16 GB RAM:lla se on vähemmän tärkeä kuin 8 GB:lla,
# mutta estää "out of memory" -kaatumiset ja nopeuttaa raskaita vaihteluita.
Set-Reg -Path $memPath -Name 'DisablePagingExecutive' -Value 0 -Type DWord `
    -Why 'Varmistetaan, että paging executive ON päällä (välttää levy-odotuksen).'

Set-Reg -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power' -Name 'HiberbootEnabled' -Value 0 -Type DWord `
    -Why 'Fast Startup pois: se tallettaa ytimen tilan levylle, pelkkä turha kirjoitus. Säästää myös käynnistysaikaa.'

# ==========================================================================================
# 5.  NVIDIA GTX 970 / DWM
# ==========================================================================================
Write-Section '5. NVIDIA GTX 970 JA GRAFIIKKAKULJER'

# TDR = Timeout Detection and Recovery. Oletus 2 s. GTX 970:n 4 GB VRAM loppuu helposti ->
# kulmayritys jumissa -> TDR nollaa kaistan ja kaatuu. Tämä on tyypillisin
# "peli toimii 3 minuuttia ja sitten kone kaatuu" -virhe.
Set-Reg -Path $gfxPath -Name 'TdrDelay' -Value 10 -Type DWord `
    -Why 'TDR-nollaus 2 s -> 10 s. VRAM loppuneen kaistan nollaus ei enää kaada koko peliä.'
Set-Reg -Path $gfxPath -Name 'TdrDDI'   -Value 20 -Type DWord `
    -Why 'Sama koskee käyttäjätilan (DDI) nollausta: 20 s palautumisaika.'

# DPC:t (keskeytyksen käsittely) jaetaan omille ydinteilleen yhteisen sijaan.
Set-Reg -Path "$gfxPath\Power" -Name 'RmGpsPsEnablePerCpuCoreDpc' -Value 1 -Type DWord `
    -Why 'NVIDIA-GPU:n DPC:t ydinkohtaisesti -> pienempi ja vakaampi frame-time.'

# HUOMI MIHIN ALKUPERÄINEN SKRIPTI TEKI VIRHEEN:
#   CoolBits / ThreadedOptimization / TripleBuffering kirjoitettiin polkuun
#   HKLM:\SOFTWARE\NVIDIA Corporation\Global\NVTweak. SE POLKU ON KUOLLUT VUODESTA 2004.
#   NVIDIA ei lue sitä lainkaan. Oikea CoolBits sijaitsee
#     HKLM:\SYSTEM\CurrentControlSet\Control\Class\{4d36e968-...}\0000 ja sitä saa
#   muokata VAIN MSI Afterburnerilla (näytönohjaimen lukitus estää muut).
#   Siksi tähän ei kirjoiteta mitään - se olisi pelkkää placeboa.
#
# Toinen virhe: per-GPU-avaimet kirjoitettiin polkuun HKLM:\SYSTEM\CurrentControlSet\Enum\PCI\...
#   joka on järjestelmän omistama ja KIRJOITUSLUKITTU myös järjestelmänvalvojalle.
#   -ErrorAction SilentlyContinue piilotti virheen, joten alkuperäisen skriptin
#   GPU-osasto ei tehnyt yhtään mitään.

# Game DVR / Game Bar -taustatallennus vie 5-15 % suorituskykyä kaikissa peleissä taustalla.
Set-Reg -Path 'HKCU:\System\GameConfigStore' -Name 'GameDVR_Enabled' -Value 0 -Type DWord `
    -Why 'Pelitallennus pois päältä (taustalla koodaa 1080p60).'
Set-Reg -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\GameDVR' -Name 'AppCaptureEnabled' -Value 0 -Type DWord `
    -Why 'Sama koskee uudemmissa Windowseissa asetuksen nimi vaihtui.'
Set-Reg -Path 'HKCU:\Software\Microsoft\GameBar' -Name 'UseNexusForGameBarEnabled' -Value 0 -Type DWord `
    -Why 'Estää Game Bar -helayksikön avaamisen, joka ei toimi Win10:ssä.'
Set-Reg -Path 'HKCU:\Software\Microsoft\GameBar' -Name 'AllowAutoGameMode' -Value 1 -Type DWord `
    -Why 'Sallii Game Moden alle.'

# GAME MODE ON: Windows sulkee taustasovellukset ja antaa pelille koko CPU:n. AITO hyöty.
Set-Reg -Path 'HKCU:\Software\Microsoft\GameBar' -Name 'AutoGameModeEnabled' -Value 1 -Type DWord `
    -Why 'Game Mode päälle. Yksi harvoista kytkimistä joka oikeasti auttaa.'

# Palautetaan normaali laskenta-aikataulu (ei kiireellistä laskentaa).
Set-Reg -Path $gfxPath -Name 'HwSchMode' -Value 1 -Type DWord `
    -Why 'Normaali ajastus. Ei vaikutusta Maxwellille, mutta palauttaa varmuuden.'

# NVIDIA Control Panel -asetukset eivät mene luotettavasti rekisteriin -> opetetaan ohjeissa lopussa.

# ==========================================================================================
# 6.  VERKKOLATENSSI
# ==========================================================================================
Write-Section '6. VERKKOLATENSSI'

# Windows kuittaa TCP-datagramit ~1/2 s välein -> ping-pong-ping. 1 = kuittaa heti.
if ($AuditOnly) {
    Write-Log '   [TULISI  ] TcpAckFrequency=1 jokaiseen verkkoliitäntään' -Color DarkGray
} else {
    foreach ($if in @(Get-ChildItem -Path $tcpPath -ErrorAction SilentlyContinue)) {
        Set-Reg -Path $if.PSPath -Name 'TcpAckFrequency' -Value 1 -Type DWord `
            -Why 'ACK lähetetään heti eikä viiveellä -> pienempi TCP-viive.'
        if ($NetworkTuning) {
            Set-Reg -Path $if.PSPath -Name 'TCPNoDelay' -Value 1 -Type DWord `
                -Why 'Nagle pois. Lyhyttä, vuorottelua sisältävää peliliikennettä varten.'
        }
    }
}

if ($NetworkTuning -and -not $AuditOnly) {
    try {
        $adapters = @(Get-NetAdapter -ErrorAction Stop | Where-Object {
            $_.Status -eq 'Up' -and $_.MediaType -eq '802.3' -and $_.Virtual -eq $false
        })
        foreach ($a in $adapters) {
            foreach ($p in @(
                @{ D = 'Interrupt Moderation';          V = 'Disabled' },
                @{ D = 'Flow Control';                  V = 'Disabled' },
                @{ D = 'Large Send Offload v2 (IPv4)';  V = 'Disabled' },
                @{ D = 'Large Send Offload v2 (IPv6)';  V = 'Disabled' },
                @{ D = 'Energy Efficient Ethernet';     V = 'Disabled' },
                @{ D = 'Green Ethernet';                V = 'Disabled' },
                @{ D = 'Power Saving Mode';             V = 'Disabled' },
                @{ D = 'Ultra Low Power Mode';          V = 'Disabled' }
            )) {
                try {
                    $cur = Get-NetAdapterAdvancedProperty -Name $a.Name -DisplayName $p.D -ErrorAction Stop
                    if ($cur.DisplayValue -ne $p.V) {
                        Set-NetAdapterAdvancedProperty -Name $a.Name -DisplayName $p.D -RegistryValue $p.V -NoRestart -ErrorAction Stop | Out-Null
                        Write-Log "   [SET     ] $($a.Name): $($p.D) = $($p.V)" -Color Green
                    } else {
                        Write-Log "   [OK      ] $($a.Name): $($p.D) = $($p.V)" -Color DarkGray
                    }
                } catch { }
            }
            try { Disable-NetAdapterPowerManagement -Name $a.Name -ErrorAction Stop | Out-Null } catch { }
        }
        Write-Log '   Verkkokortit ilman automaattista virransäästöä. HUOM: nostaa Idle-tehon ja voi aiheuttaa ääntä.' -Color Yellow
    } catch {
        Write-Log "   Verkkokortin asetuksia ei voitu asettaa: $($_.Exception.Message)" -Color DarkGray
    }
} elseif ($AuditOnly) {
    Write-Log '   [TULISI  ] verkkokortin Interrupt Moderation pois (-NetworkTuning)' -Color DarkGray
}

# ==========================================================================================
# 7.  SYÖTE
# ==========================================================================================
Write-Section '7. HIIRI JA SYÖTEVIIVE'

# MouseSpeed = 0: TÄMÄ ON OIKEA Windows-asetus ja poistaa hiiren kiihdytyksen.
# (MouseThreshold1/2, joita alkuperäinen skripti asetti, ovat Windows 95/98 -jäänneitä.)
Set-Reg -Path 'HKCU:\Control Panel\Mouse' -Name 'MouseSpeed' -Value '0' -Type String `
    -Why 'Hiiren kiihdytys pois. Vaikuttaa vain vanhoihin peleihin joissa ei ole raw input -tilaa.'
Set-Reg -Path 'HKCU:\Control Panel\Mouse' -Name 'DoubleClickSpeed' -Value '600' -Type String `
    -Why 'Kaksoisnapsautuksen raja 600 ms (oletus 500). Pelaajalle hyötyä, ei haittaa.'

# ==========================================================================================
# 8.  TAUSTAPALVELUT  (vain -TrimServices)
# ==========================================================================================
Write-Section '8. TAUSTAPALVELUT'

if ($TrimServices) {
    # Valikoitu lista. Defender, Windows Update, palomuuri, BitLocker, WaaSMedic,
    # SecurityHealth ja Event Log on TARKOITUKSELLA pois listalta.
    $trim = [ordered]@{
        'DiagTrack'          = 'Microsoft-telemetria. Kerää koneeltasi tietoja jatkuvasti.'
        'dmwappushservice'   = 'Puhelimen sovelluspush. Ei tarvita.'
        'MapsBroker'         = 'Offline Maps. Ei tarvita.'
        'lfsvc'              = 'Paikannuspalvelu. Ei tarvita; kuluttaa akkua ja verkkoa.'
        'Fax'                = 'Faksipalvelu. 2000-luvun perintöä.'
        'RetailDemo'         = 'Kauppakohtien esittelyapuri.'
        'WMPNetworkSvc'      = 'Media Player -verkkostriimaus. Ei tarvita.'
        'XblAuthManager'     = 'Xbox-tunnus. Ei tarvita ilman cloud-tallennusta.'
        'XblGameSave'        = 'Xbox Game Save -synkronointi.'
        'XboxNetApiSvc'      = 'Xbox-verkkopalvelu.'
        'XboxGipSvc'         = 'Xbox Input -palvelu.'
        'Spooler'            = 'Tulostusjonotus. POISTA VAIN jos et tulosta.'
        'WiaSvc'             = 'Kuvien hankinta. POISTA VAIN jos et liitä skanneria/kameraa.'
        'icssvc'             = 'Mobile Hotspot. POISTA VAIN jos et jaa wifiä.'
        'TabletInputService' = 'Kynäsyöte. POISTA VAIN jos koneessa ei ole kynää.'
    }
    foreach ($k in $trim.Keys) {
        if (-not (Get-Service -Name $k -ErrorAction SilentlyContinue)) {
            Write-Log "   [OHITETTU ] $k (ei asennettu tähän koneeseen)" -Color DarkGray
            continue
        }
        Set-ServiceStart -Name $k -Start 4 -Why $trim[$k] -Stop
    }
} else {
    Write-Log '   Ohitettu. Aja -TrimServices jos haluat poistaa turhat palvelut.' -Color DarkGray
}

# ==========================================================================================
# 9.  KOKEELLISET ASETUKSET  (oletus: EI KÄYTÖSSÄ)
# ==========================================================================================
# NÄMÄ OVAT NE ASETUKSET, JOITA "MAAILMAN PARHAAT OPTIMIZERIT" LAISKAAVAT SINULLE.
# Useimmat ovat placeboa ja osa on VAARALLISIA. Rehellinen lista:
#
#   disabledynamictick yes   POISTAA dynaamisen kellon. Tämä on tuottajuutta aiheuttava
#                            temppu: joko jäädyttää koneen, aiheuttaa 10 ms -tason
#                            napping-viiveen tai hidastaa kaikkea. Windows 10 2004+ ohittaa
#                            sen kokonaan. TÄMÄ SKRIPTI NOLLAAA SEN AINA (kohta 9b).
#   useplatformtick/clock    HPET. Samoin vanhentunut ja HPET on oletuksena jo pois.
#   tscsyncpolicy            BIOS/ACPI-synkronointi 2000-luvulta. Ei mitään.
#   intelppm-parametrit      KAIKKI ALLE (L3_Cache_Foreground_Priority, LLC_ForegroundMonopoly,
#                            IMC_Scrubber_Disable, Cache_Locality_Strict, LatencyToleranceValue,
#                            Ring_Bus_Priority_Mode, HWP_EPP, Boost_Policy, HWP_Enable,
#                            Cache_QoS_Enable, IMC_Power_Down_Enable, IMC_Opportunistic_
#                            Refresh_Disable, InterruptToleranceValue, HWP_Interrupt_Mode,
#                            HWP_PerformanceSetting) ovat KEHITTÄJÄN KATKAISUJA, joita Intel
#                            ei lue Windowsista lainkaan. Nolla vaikutus + turha rekisteri.
#   intelppm/intelpep        Intel ei lue Windowsista myöskään näitä. DisableD3Hot,
#                            DisableRuntimePowerManagement, DisableL1Substates,
#                            DisablePchClockGating ja DmiLinkPriority nostavat tehonkulutusta
#                            20-40 W ilman yhtään lisä-FPS:ää -> pieni kone vain kuumenee.
#   Session Manager\kernel   MaximumDpcStackDepth poistettiin Win8:sta. MinimumDpcRate ja
#                            LFH_Aggressive_Enable eivät ole Win10:ssä olemassa.
#   PriorityControl          InterruptPrioritySeparation ja IRQ12Priority eivät ole
#                            olemassa. IRQ-arvo 1 on lisäksi virheellinen (sallittu 16-31).
#   Enum\PCI\...\MSISupported  Kirjoitus estetty + MSI on jo oletuksena päällä Intel 100-sarjassa.
#   DisableWriteCombining    VAARALLINEN GPU:lle: poistaa kirjoitusten yhdistämisen ja voi
#                            selvästi hidastaa. Tämä on yleisin "optimointi" joka oikeasti
#                            tekee HAATTAA.
#   RMPcieLinkSpeed = 4      Pakottaa PCIe-sukupolven jota Maxwell ei tue -> voi pudottaa
#                            väylän Gen1:een. Älä koskaan.
#   DisableVRAMCompression  Maxwellilla ei ole mitään puristettavaa.
#   MouseDataQueueSize       Suurempi tapahtumajono -> enemmän DPC:tä ja epävakaampia
#                            frame-aikoja joillakin emolevyillä.

if ($Experimental) {
    Write-Section '9. KOKEELLISET ASETUKSET (KÄYTÖSSÄ - vain vianmääritystä varten)'

    $intelPpm = 'HKLM:\SYSTEM\CurrentControlSet\Services\intelppm\Parameters'
    foreach ($v in @(
        @{ N = 'L3_Cache_Foreground_Priority';        V = 31 },
        @{ N = 'LLC_ForegroundMonopoly';            V = 1  },
        @{ N = 'Cache_Locality_Strict';             V = 1  },
        @{ N = 'IMC_Scrubber_Disable';               V = 1  },
        @{ N = 'IMC_Power_Down_Enable';              V = 0  },
        @{ N = 'IMC_Opportunistic_Refresh_Disable';  V = 1  },
        @{ N = 'Ring_Bus_Priority_Mode';             V = 1  },
        @{ N = 'RingBusPriority';                    V = 1  },
        @{ N = 'Cache_QoS_Enable';                   V = 1  },
        @{ N = 'LatencyToleranceValue';              V = 0  },
        @{ N = 'InterruptToleranceValue';            V = 0  },
        @{ N = 'HWP_Enable';                         V = 1  },
        @{ N = 'HWP_Interrupt_Mode';                 V = 1  },
        @{ N = 'HWP_EPP';                            V = 0  },
        @{ N = 'HWP_PerformanceSetting';             V = 1  },
        @{ N = 'Boost_Policy';                      V = 1  }
    )) { Set-Reg -Path $intelPpm -Name $v.N -Value $v.V -Type DWord -Why 'PLACEBO - Intel ei lue tätä arvoa' }

    $intelPep = 'HKLM:\SYSTEM\CurrentControlSet\Services\intelpep\Parameters'
    foreach ($v in @(
        @{ N = 'DisableD3Hot';                  V = 1 },
        @{ N = 'DisableRuntimePowerManagement'; V = 1 },
        @{ N = 'DisableL1Substates';            V = 1 },
        @{ N = 'DisablePchClockGating';         V = 1 },
        @{ N = 'PkgCStateLimit';                V = 0 },
        @{ N = 'TimerCoalescingEnable';         V = 0 },
        @{ N = 'DmiLinkPriority';               V = 3 }
    )) { Set-Reg -Path $intelPep -Name $v.N -Value $v.V -Type DWord -Why 'PLACEBO ja/tai lisää tehonkulutusta' }

    $kPath = 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\kernel'
    Set-Reg -Path $kPath -Name 'MaximumDpcStackDepth' -Value 512 -Type DWord -Why 'PLACEBO - poistettu Win8:sta'
    Set-Reg -Path $kPath -Name 'MinimumDpcRate'       -Value 100 -Type DWord -Why 'PLACEBO - ei ole Win10:ssä'
    Set-Reg -Path $kPath -Name 'LFH_Aggressive_Enable' -Value 1   -Type DWord -Why 'PLACEBO - ei ole Win10:ssä'

    Set-Reg -Path $prioPath -Name 'InterruptPrioritySeparation' -Value 4 -Type DWord -Why 'PLACEBO - ei ole olemassa'
    Set-Reg -Path $prioPath -Name 'IRQ12Priority'              -Value 1 -Type DWord -Why 'VIRHEELLINEN arvo (sallittu 16-31)'

    $gfxPower = "$gfxPath\Power"
    Set-Reg -Path $gfxPower -Name 'InvalidateDynamicPstate' -Value 1 -Type DWord -Why 'PLACEBO - ei ole dokumentoitu'
    Set-Reg -Path $gfxPower -Name 'RmDisableRegistryCaching' -Value 1 -Type DWord -Why 'PLACEBO - voi aiheuttaa portin uudelleenohjauksen'
    Set-Reg -Path $gfxPower -Name 'DisableL1LowPower'        -Value 1 -Type DWord -Why 'ei vaikutusta Maxwellille'

    Set-Reg -Path 'HKLM:\SYSTEM\CurrentControlSet\Services\mouclass\Parameters' -Name 'MouseDataQueueSize' -Value 500 -Type DWord `
        -Why 'Isompi hiiritapahtumajono. Palauta arvoon 100 jos FPS alkaa nykäistä.'

    Set-Reg -Path $dispClass -Name 'DisableWriteCombining' -Value 1 -Type DWord `
        -Why 'VAARALLINEN GPU:lle - voi hidastaa selvästi. Kokeillaan vain tiedon vuoksi.'
    Set-Reg -Path $dispClass -Name 'DisableVRAMCompression' -Value 1 -Type DWord -Why 'Maxwellilla ei sovellu'
    Set-Reg -Path $dispClass -Name 'RMPcieLinkSpeed' -Value 4 -Type DWord `
        -Why 'VAARALLINEN - voi pakottaa PCIe Gen1:een ja pudottaa suorituskykyä.'
    Set-Reg -Path $dispClass -Name 'MSISupported' -Value 1 -Type DWord -Why 'MSI (viestipohjainen keskeytys) päälle'
    Set-Reg -Path $dispClass -Name 'DisableAspm'  -Value 1 -Type DWord -Why 'Laitteen ASPM pois: +5-15 W tehoa'
}

# ==========================================================================================
# 9b. VAARALLISTEN AJASTINASETUSTEN NOLLAUS (AINA, myös ilman -Experimental-lippua)
# ==========================================================================================
Write-Section '9b. VAARALLISTEN AJASTIMASETUSTEN NOLLAUS (AINA)'

# Jos jokin aiempi "optimizer" tai asennusohjelma on jo asettanut nämä, ne ovat koneesi
# ykkäskilpailun aiheuttajia. Palautetaan Windowsin oletukset.
foreach ($kv in @(
    @{ K = 'disabledynamictick'; V = 'no' },
    @{ K = 'useplatformtick';   V = 'no' },
    @{ K = 'useplatformclock';  V = 'no' }
)) {
    if ($AuditOnly) { Write-Log "   [TULISI  ] bcdedit /set $($kv.K) $($kv.V)" -Color DarkGray; continue }
    try {
        $out = (& bcdedit /set $kv.K $kv.V 2>&1) -join ' '
        Write-Log "   [SET     ] bcdedit $($kv.K) $($kv.V)  ->  $out" -Color Green
    } catch {
        Write-Log "   [EI ONNISTU] bcdedit $($kv.K): $($_.Exception.Message)" -Color Red
    }
}
if ($AuditOnly) {
    Write-Log '   [TULISI  ] bcdedit /deletevalue tscsyncpolicy' -Color DarkGray
} else {
    try {
        (& bcdedit /deletevalue tscsyncpolicy 2>&1) | Out-Null
        Write-Log '   [POISTETTU] bcdedit tscsyncpolicy (BIOS/ACPI-synk, vanhaa perua)' -Color Yellow
    } catch { }
}

# ==========================================================================================
# 10. RAPORTTI, PALAUTUS JA KÄYNNISTYS
# ==========================================================================================
Write-Section '10. RAPORTTI'
Write-Log ("   Muutoksia tehty : {0}" -f $script:Changed)
Write-Log ("   Epäonnistuneita : {0}" -f $script:Failed)
Write-Log ("   Varmuuskopioita : {0} .reg" -f $script:Backups.Count)

if (-not $AuditOnly) {
    $restore = @"
# NIGHTBLADE X2 OPTIMIZER - TÄYDELLINEN PALAUTUS
# Aja tämä JÄRJESTELMÄNVALVOJANA. Palauttaa kaikki rekisteriavaimet JA palveluiden
# Start-arvot tilaan, joka oli ennen optimointia. Käynnistä lopuksi kone uudelleen.
`$ErrorActionPreference = 'Continue'
Write-Host "Palautetaan $($script:Backups.Count) avainta kansiosta: $Backup" -ForegroundColor Cyan
Get-ChildItem -Path '$Backup' -Filter '*.reg' | Sort-Object Name -Descending | ForEach-Object {
    Write-Host ("  -> " + `$_.Name) -ForegroundColor DarkGray
    & reg.exe import `$_.FullName 2>`$null | Out-Null
}
# Power-suunnitelma ja bcdedit-arvot palautetaan käsin:
#   powercfg /setactive 8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c   (Balanced / oletus)
Write-Host "Rekisterit palautettu. Käynnistä kone uudelleen." -ForegroundColor Green
Write-Host "Kopioita alkuperäisistä avaimista: $Backup" -ForegroundColor DarkGray
"@
    Set-Content -Path (Join-Path $Backup 'RESTORE.ps1')    -Value $restore         -Encoding UTF8
    Set-Content -Path (Join-Path $Backup 'changes.log')     -Value ($script:Log -join "`r`n") -Encoding UTF8
    Write-Log "   Palautusskriptti : $Backup\RESTORE.ps1" -Color Cyan
    Write-Log "   Varmuuskopiot    : $Backup" -Color Cyan
}

Write-Host ''
Write-Host '##########################################################################' -ForegroundColor Green
Write-Host '# OPTIMOINTI VALMIS                                                          #' -ForegroundColor Green
Write-Host '##########################################################################' -ForegroundColor Green
Write-Host ''
if ($AuditOnly) {
    Write-Host 'TAMA VAIN OLI TARKISTUS (-AuditOnly). Mitään ei muutettu.' -ForegroundColor Cyan
    Write-Host 'Aja uudelleen ilman -AuditOnly-lippua ollaksesi varma.' -ForegroundColor Cyan
} else {
    Write-Host 'KAYNNISTA KONE UUDELLEEN.' -ForegroundColor Cyan
    Write-Host 'Palauta kaikki myohemmin: <varmuuskopiohakemisto>\RESTORE.ps1' -ForegroundColor DarkGray
}
Write-Host ''

Write-Host 'JATKOTOIMI KONEELLA - TAMMA ON KAIKKIEN TAPAHTUMIEN JOHTO:' -ForegroundColor Yellow
Write-Host ''
Write-Host ' 1. NVIDIA CONTROL PANEL (napsauta hiirella oikealla ruudulla) -> Hallitse 3D-asetukset' -ForegroundColor White
Write-Host '    Virranhallintatila     : Suurin suorituskyky (EI "Optimoitu")' -ForegroundColor DarkGray
Write-Host '    Viiveen optimointi    : Ultra (EI "Normaali", koska yllä on jo rekisterisäädöt)' -ForegroundColor DarkGray
Write-Host '    Tekstuurisuodatus     : Korkea suorituskyky' -ForegroundColor DarkGray
Write-Host '    Varjostinvälimuisti   : Rajoittamaton' -ForegroundColor DarkGray
Write-Host '    Pystysynkronointi     : pois (V-Sync päällessä tuhoaa sekä latenssin että syöttöviiveen)' -ForegroundColor DarkGray
Write-Host '    Moninäyttötila        : pois (yksi näyttö)' -ForegroundColor DarkGray
Write-Host '    Low Latency Mode      : Ultra VAIN jos et käytä V-Syncia. Ne ovat ristiriidassa.' -ForegroundColor DarkGray
Write-Host ''
Write-Host ' 2. MSI AFTERBURNER (ilmainen) - TAMA ON YKSI AITOISTA VOITTOISTA' -ForegroundColor Yellow
Write-Host '    Power Limit  = 90-95 %  -> alhaisempi lämpötila, lähes sama FPS' -ForegroundColor White
Write-Host '    Temp Limit   = 80-83 C' -ForegroundColor DarkGray
Write-Host '    Core Clock   = +50...+100 MHz (pieni askel kerrallaan, testaa aina)' -ForegroundColor DarkGray
Write-Host '    Memory Clock = +200...+400 MHz' -ForegroundColor DarkGray
Write-Host '    Fan Speed    : käyrä 30 % -> 100 % lämpötilan > 75 C. Nightblade X2:n jahdutus on ahtaa.' -ForegroundColor DarkGray
Write-Host '    Aja vähintään 10-15 min ennen kuin jätät asetukset pysyvästi.' -ForegroundColor DarkGray
Write-Host ''
Write-Host ' 3. NÄMÄ OVAT OIKEASTI KAIKKIEN ASIOIDEN LÄHDENNE' -ForegroundColor Yellow
Write-Host '    a) GTX 970 4 GB RAJOITTAA KOKONAISEN SUORITUSKYKYSYSI. 1080p:ssa yli 4 GB' -ForegroundColor White
Write-Host '       peleissä VRAM loppuu ja kuva räpäisee. Yksikään rekisteri ei korjaa VRAM-puutta.' -ForegroundColor DarkGray
Write-Host '    b) RAM: 2x8 GB DUAL CHANNEL. Jos sinulla on 1x16 GB, osta toinen 8 GB.' -ForegroundColor White
Write-Host '       Se on halvin yksittäinen paikka mihin laittaa rahat tällä summalla.' -ForegroundColor DarkGray
Write-Host '    c) Puhdista jahdutus ja mittaa lämpötilat (HWiNFO64). Puhdas kone + tahma päälle' -ForegroundColor White
Write-Host '       voi olla 5-15 C viileämpi -> pidempi boost ja vähemmän throttlingia.' -ForegroundColor DarkGray
Write-Host '    d) Varmista, ettei "virtalähde" ole alle 500 W. Pieni 250-300 W -PSU on ylein' -ForegroundColor White
Write-Host '       ongelma, kun päivityksessä on tullut uusi kortti mukana.' -ForegroundColor DarkGray
Write-Host '    e) ATLAS OS: älä koskaan laita "Realtime Priority" -tilaa päällä. Se voi jättää' -ForegroundColor White
Write-Host '       järjestelmän ilman prioriteettia, kun joku taustaprosessi lopettaa.' -ForegroundColor DarkGray
Write-Host ''
Write-Host ' 4. JOS HALUAT OIKEASTI ENEMMÄN FPS:Ä, LOPETA OPTIMOINTI JA OSTA' -ForegroundColor Yellow
Write-Host '    RX 6600 8GB tai RTX 3060 12GB noin 160-200 e. Ne ovat 3-4x nopeampia kuin' -ForegroundColor White
Write-Host '    GTX 970 ja antavat 2-4x VRAM:ia. Se on ainoa muutos joka ratkaisee 1080p-mitet.' -ForegroundColor White
Write-Host '    KAIKKI tämän skriptin rekisterit yhteensä = 0-3 %.' -ForegroundColor DarkGray
