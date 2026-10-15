param(
    [Parameter(Mandatory = $true)][string]$SourceRoot,
    [Parameter(Mandatory = $true)][string]$DestinationRoot,
    [Parameter(Mandatory = $true)][string]$HexColor,
    [string]$BadgeText = ''
)

Add-Type -AssemblyName System.Drawing

$target = [System.Drawing.ColorTranslator]::FromHtml($HexColor)
$relativeAssets = @(
    'res\drawable\app_banner.png',
    'res\mipmap-hdpi\ic_app.png',
    'res\mipmap-hdpi\ic_launcher.png',
    'res\mipmap-mdpi\ic_app.png',
    'res\mipmap-mdpi\ic_launcher.png',
    'res\mipmap-xhdpi\ic_app.png',
    'res\mipmap-xhdpi\ic_launcher.png',
    'res\mipmap-xxhdpi\ic_launcher.png',
    'res\mipmap-xxxhdpi\ic_launcher.png'
)

foreach ($relative in $relativeAssets) {
    $source = Join-Path $SourceRoot $relative
    if (-not (Test-Path -LiteralPath $source)) { continue }

    $destination = Join-Path $DestinationRoot $relative
    $destinationDirectory = Split-Path -Parent $destination
    New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null

    $inputBitmap = [System.Drawing.Bitmap]::FromFile($source)
    $outputBitmap = New-Object System.Drawing.Bitmap $inputBitmap.Width, $inputBitmap.Height,
        ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        $minX = $inputBitmap.Width
        $minY = $inputBitmap.Height
        $maxX = -1
        $maxY = -1
        for ($y = 0; $y -lt $inputBitmap.Height; $y++) {
            for ($x = 0; $x -lt $inputBitmap.Width; $x++) {
                $pixel = $inputBitmap.GetPixel($x, $y)
                if ($pixel.R -gt 80 -and $pixel.R -gt ($pixel.G * 1.35) -and $pixel.R -gt ($pixel.B * 1.35)) {
                    if ($x -lt $minX) { $minX = $x }
                    if ($x -gt $maxX) { $maxX = $x }
                    if ($y -lt $minY) { $minY = $y }
                    if ($y -gt $maxY) { $maxY = $y }
                    # Preserve the original anti-aliased blend against white at the logo edge.
                    $whiteMix = [Math]::Min($pixel.G, $pixel.B) / 255.0
                    $redStrength = 1.0 - $whiteMix
                    $r = [int][Math]::Round($target.R * $redStrength + 255 * $whiteMix)
                    $g = [int][Math]::Round($target.G * $redStrength + 255 * $whiteMix)
                    $b = [int][Math]::Round($target.B * $redStrength + 255 * $whiteMix)
                    $outputBitmap.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($pixel.A, $r, $g, $b))
                } else {
                    $outputBitmap.SetPixel($x, $y, $pixel)
                }
            }
        }

        if ($BadgeText -and $maxX -ge $minX -and $maxY -ge $minY) {
            $graphics = [System.Drawing.Graphics]::FromImage($outputBitmap)
            try {
                $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
                $logoHeight = $maxY - $minY + 1
                $badgeHeight = [Math]::Max(12, [int]($logoHeight * 0.34))
                $badgeWidth = if ($BadgeText -eq '+') { $badgeHeight } else { [int]($badgeHeight * 1.55) }
                $badgeX = [Math]::Min($inputBitmap.Width - $badgeWidth - 1, [int]($maxX - $badgeWidth * 0.45))
                $badgeY = [Math]::Max(1, [int]($minY - $badgeHeight * 0.18))
                $badgeRect = New-Object System.Drawing.RectangleF $badgeX, $badgeY, $badgeWidth, $badgeHeight
                $whiteBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
                $textBrush = New-Object System.Drawing.SolidBrush $target
                $fontSize = if ($BadgeText -eq '+') { $badgeHeight * 0.68 } else { $badgeHeight * 0.48 }
                $font = New-Object System.Drawing.Font 'Arial', $fontSize, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
                $format = New-Object System.Drawing.StringFormat
                $format.Alignment = [System.Drawing.StringAlignment]::Center
                $format.LineAlignment = [System.Drawing.StringAlignment]::Center
                try {
                    $graphics.FillEllipse($whiteBrush, $badgeRect)
                    $graphics.DrawString($BadgeText, $font, $textBrush, $badgeRect, $format)
                } finally {
                    $format.Dispose()
                    $font.Dispose()
                    $textBrush.Dispose()
                    $whiteBrush.Dispose()
                }
            } finally {
                $graphics.Dispose()
            }
        }
        $outputBitmap.Save($destination, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $inputBitmap.Dispose()
        $outputBitmap.Dispose()
    }
}
