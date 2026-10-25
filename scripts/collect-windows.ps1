# ============================================================
# Windows 桌面信息采集脚本（给排障助手用）
#
# 用法（在员工电脑上以普通用户运行）：
#   powershell -ExecutionPolicy Bypass -File collect-windows.ps1
#   powershell -ExecutionPolicy Bypass -File collect-windows.ps1 -OutFile C:\temp\win-info.txt
#
# 输出是一段文本，直接粘进排障助手的「日志」框即可。
# 只读采集，不改系统；采集项都做了容错，单项失败不影响整体。
# ============================================================
param(
    [string]$OutFile = "",
    [int]$Hours = 24
)

$ErrorActionPreference = "Continue"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$sb = New-Object System.Text.StringBuilder
function W([string]$s) { [void]$sb.AppendLine($s) }
function Try-Run([string]$title, [scriptblock]$block) {
    W "### $title"
    try { & $block } catch { W ("（采集失败：" + $_.Exception.Message + "）") }
    W ""
}

$os = Get-CimInstance Win32_OperatingSystem
$cs = Get-CimInstance Win32_ComputerSystem

W "==== Windows 桌面采集信息 ===="
W ("采集时间：" + (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
W ("主机名：" + $env:COMPUTERNAME + "｜域：" + $cs.Domain)
W ("系统：" + $os.Caption + " " + $os.Version + "（" + $os.OSArchitecture + "）")
W ("上次启动：" + $os.LastBootUpTime)
W ("内存：" + [math]::Round($cs.TotalPhysicalMemory/1GB,1) + " GB，可用 " + [math]::Round($os.FreePhysicalMemory/1MB,1) + " GB")
W ""

Try-Run "CPU 与负载" {
    $cpu = Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average
    W ("CPU 平均占用：" + [int]$cpu.Average + "%")
    W ("逻辑处理器：" + $env:NUMBER_OF_PROCESSORS)
}

Try-Run "磁盘" {
    Get-CimInstance Win32_LogicalDisk -Filter "DriveType=3" | ForEach-Object {
        $free = [math]::Round($_.FreeSpace/1GB,1); $total = [math]::Round($_.Size/1GB,1)
        W ("{0} 可用 {1} GB / 共 {2} GB" -f $_.DeviceID, $free, $total)
    }
}

Try-Run "占用最高的进程（内存）" {
    Get-Process | Sort-Object WorkingSet64 -Descending | Select-Object -First 8 |
        ForEach-Object { W ("{0,-28} 内存 {1,7:N0} MB  CPU {2,6:N1} s" -f $_.ProcessName, ($_.WorkingSet64/1MB), $_.CPU) }
}

Try-Run "自动启动但未运行的服务" {
    $bad = Get-Service | Where-Object { $_.StartType -eq "Automatic" -and $_.Status -ne "Running" }
    if ($bad) { $bad | ForEach-Object { W ("{0}（{1}）" -f $_.Name, $_.DisplayName) } } else { W "无" }
}

Try-Run "最近 $Hours 小时系统日志错误" {
    $since = (Get-Date).AddHours(-$Hours)
    Get-WinEvent -FilterHashtable @{ LogName="System"; Level=1,2; StartTime=$since } -MaxEvents 15 -ErrorAction SilentlyContinue |
        ForEach-Object { W ("{0:MM-dd HH:mm} ID={1} {2}" -f $_.TimeCreated, $_.Id, $_.ProviderName) }
}

Try-Run "最近 $Hours 小时应用日志错误" {
    $since = (Get-Date).AddHours(-$Hours)
    Get-WinEvent -FilterHashtable @{ LogName="Application"; Level=1,2; StartTime=$since } -MaxEvents 15 -ErrorAction SilentlyContinue |
        ForEach-Object { W ("{0:MM-dd HH:mm} ID={1} {2}" -f $_.TimeCreated, $_.Id, $_.ProviderName) }
}

Try-Run "网络配置" {
    Get-NetIPConfiguration | Where-Object { $_.IPv4Address } | ForEach-Object {
        W ("网卡：" + $_.InterfaceAlias + "  IP：" + ($_.IPv4Address.IPAddress -join ",") + "  网关：" + ($_.IPv4DefaultGateway.NextHop -join ","))
    }
    W ("DNS：" + ((Get-DnsClientServerAddress -AddressFamily IPv4 | Where-Object { $_.ServerAddresses } | ForEach-Object { $_.ServerAddresses }) -join ", "))
}

Try-Run "打印与共享相关服务" {
    foreach ($n in "Spooler","LanmanWorkstation","LanmanServer") {
        $s = Get-Service -Name $n -ErrorAction SilentlyContinue
        if ($s) { W ("{0}：{1}" -f $s.DisplayName, $s.Status) }
    }
}

Try-Run "近期系统更新" {
    Get-HotFix | Sort-Object InstalledOn -Descending | Select-Object -First 5 |
        ForEach-Object { W ("{0} {1}" -f $_.HotFixID, $_.InstalledOn) }
}

$text = $sb.ToString()
$text | Write-Output
if ($OutFile) { $text | Out-File -FilePath $OutFile -Encoding UTF8; Write-Host ("已保存到 " + $OutFile) -ForegroundColor Green }
