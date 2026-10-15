param(
    [Parameter(Mandatory = $true)]
    [string]$DecodedRoot,
    [int]$WebPort = 8888,
    [int]$SocksPort = 9876
)

$coatRoot = Join-Path $DecodedRoot 'smali_classes2\dev\cobalt\coat'
$proxySmali = Join-Path $coatRoot 'ProxyHelper.smali'
if (-not (Test-Path -LiteralPath $proxySmali)) {
    throw "ProxyHelper.smali not found: $proxySmali"
}

$webHex = '0x' + [Convert]::ToString($WebPort, 16)
$socksHex = '0x' + [Convert]::ToString($SocksPort, 16)
Get-ChildItem -LiteralPath $coatRoot -Filter 'ProxyHelper*.smali' | ForEach-Object {
    $content = Get-Content -LiteralPath $_.FullName -Raw
    $content = $content.Replace('0x22b8', $webHex)
    $content = $content.Replace('0x2694', $socksHex)
    $content = $content.Replace('8888', [string]$WebPort)
    $content = $content.Replace('9876', [string]$SocksPort)
    [System.IO.File]::WriteAllText($_.FullName, $content, [System.Text.UTF8Encoding]::new($false))
}

$text = Get-Content -LiteralPath $proxySmali -Raw
$start = $text.IndexOf('function fetchSponsorBlock(vid) {')
$end = $text.IndexOf('    function checkVideo() {', $start)
if ($start -lt 0 -or $end -lt 0) {
    throw 'Injected SponsorBlock function was not found'
}

# Keep the proven localhost transport as the primary path, with the native
# title observer as a fallback. Each installed variant has a different port.
$newFunction = "function fetchSponsorBlock(vid) {\n        try {\n            fetch('http://127.0.0.1:$WebPort/api/sponsorblock?v=' + encodeURIComponent(vid), {cache:'no-store'})\n                .then(function(r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })\n                .then(function(data) { window.__yttv_set_segments(vid, Array.isArray(data) ? data : []); })\n                .catch(function(e) { try { document.title = 'YTTV_VID:' + vid + ':' + Date.now(); } catch(ignored) {} });\n        } catch(e) { try { document.title = 'YTTV_VID:' + vid + ':' + Date.now(); } catch(ignored) {} }\n    }\n\n"
$text = $text.Substring(0, $start) + $newFunction + $text.Substring($end)

[System.IO.File]::WriteAllText($proxySmali, $text, [System.Text.UTF8Encoding]::new($false))

$dpvSmali = Join-Path $DecodedRoot 'smali_classes2\dpv.smali'
if (Test-Path -LiteralPath $dpvSmali) {
    $dpv = Get-Content -LiteralPath $dpvSmali -Raw
    $hookCall = 'Ldev/cobalt/coat/ProxyHelper;->captureMediaUrl(Ljava/lang/String;)V'
    if (-not $dpv.Contains($hookCall)) {
        $methodStart = $dpv.IndexOf('.method public static h(Lewb;')
        $marker = "    move-result-object v2"
        $markerPos = $dpv.IndexOf($marker, $methodStart)
        if ($methodStart -lt 0 -or $markerPos -lt 0) { throw 'dpv media URL hook marker not found' }
        $markerPos += $marker.Length
        $hook = "`r`n`r`n    invoke-static {v2}, $hookCall`r`n"
        $dpv = $dpv.Substring(0, $markerPos) + $hook + $dpv.Substring($markerPos)
        [System.IO.File]::WriteAllText($dpvSmali, $dpv, [System.Text.UTF8Encoding]::new($false))
    }
}
$ltlSmali = Join-Path $DecodedRoot 'smali_classes2\ltl.smali'
if (Test-Path -LiteralPath $ltlSmali) {
    $ltl = Get-Content -LiteralPath $ltlSmali -Raw
    $hookCall = 'Ldev/cobalt/coat/ProxyHelper;->captureMediaUrl(Ljava/lang/String;)V'
    if (-not $ltl.Contains($hookCall)) {
        $ctor = $ltl.IndexOf('.method public constructor <init>(Ljava/lang/String;Lorg/chromium/net/UrlRequest$Callback;Ljava/util/concurrent/Executor;Llsd;)V')
        $marker = '    iput-object p1, p0, Lltl;->c:Ljava/lang/String;'
        $at = $ltl.IndexOf($marker, $ctor)
        if ($ctor -lt 0 -or $at -lt 0) { throw 'Cronet builder media URL hook marker not found' }
        $at += $marker.Length
        $ltl = $ltl.Substring(0, $at) + "`r`n`r`n    invoke-static {p1}, $hookCall`r`n" + $ltl.Substring($at)
        [System.IO.File]::WriteAllText($ltlSmali, $ltl, [System.Text.UTF8Encoding]::new($false))
    }
}
Write-Output "Patched SponsorBlock and ports in $DecodedRoot (web=$WebPort, socks=$SocksPort)"
