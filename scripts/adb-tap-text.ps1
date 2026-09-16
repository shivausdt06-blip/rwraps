param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string]$Text
)

$adb = "D:\Andriod\Sdk\platform-tools\adb.exe"
$remote = "/sdcard/arl-ui.xml"
& $adb -s $Serial shell uiautomator dump $remote | Out-Null
$local = Join-Path $env:TEMP "arl-ui-$Serial.xml"
& $adb -s $Serial pull $remote $local | Out-Null
[xml]$xml = Get-Content $local
$node = $xml.SelectNodes("//node[@text='$Text' or @content-desc='$Text']") | Select-Object -First 1
if (-not $node) {
    Write-Error "No node with text '$Text' on $Serial"
    exit 1
}
$bounds = $node.bounds -replace '\[|\]', '' -split ','
$x = [int](([int]$bounds[0] + [int]$bounds[2]) / 2)
$y = [int](([int]$bounds[1] + [int]$bounds[3]) / 2)
Write-Host "Tapping '$Text' at $x,$y on $Serial"
& $adb -s $Serial shell input tap $x $y
