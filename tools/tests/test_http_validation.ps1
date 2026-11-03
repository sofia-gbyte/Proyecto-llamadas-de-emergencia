param(
    [Parameter(Mandatory = $true)]
    [string]$AppImageDirectory
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$script:passed = 0
$script:failed = 0
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$AppImageDirectory = [System.IO.Path]::GetFullPath($AppImageDirectory)
$executable = Join-Path $AppImageDirectory 'CODES.exe'
$runtimeModules = Join-Path $AppImageDirectory 'runtime\lib\modules'

if (-not (Test-Path -LiteralPath $executable) -or -not (Test-Path -LiteralPath $runtimeModules)) {
    throw "App-image incompleta: $AppImageDirectory"
}

$environmentNames = @(
    'CODES_DATA_DIR', 'CODES_SERVER_PORT', 'CODES_SERVER_ADDRESS', 'CODES_ADMIN_USER',
    'CODES_ADMIN_PASSWORD', 'CODES_JWT_SECRET', 'CODES_ENCRYPT_KEY',
    'CODES_ALTCHA_ENABLED', 'CODES_ALTCHA_SECRET', 'CODES_AUDIT_LOG_PATH'
)
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

$dataDirectory = Join-Path $env:TEMP ('codes-http-validation-' + [guid]::NewGuid().ToString('N'))
$adminUsername = 'httpadmin' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$adminPassword = 'Http-Test-Admin-2026!'
$firstUsername = 'operator' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$secondUsername = 'backup' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$operatorPassword = 'Http-Test-Operator-2026!'
$startupProcess = $null
$httpClient = $null
$serverPort = 0
$baseUrl = ''

function Assert-Status {
    param([string]$Name, [object]$Response, [int]$ExpectedStatus)

    if ($Response.Status -ne $ExpectedStatus) {
        $script:failed++
        throw "FAIL: $Name; expected HTTP $ExpectedStatus, received $($Response.Status): $($Response.Content)"
    }

    $script:passed++
    Write-Host "PASS: $Name (HTTP $ExpectedStatus)"
}

function Assert-Condition {
    param([string]$Name, [bool]$Condition)

    if (-not $Condition) {
        $script:failed++
        throw "FAIL: $Name"
    }

    $script:passed++
    Write-Host "PASS: $Name"
}

function Send-HttpRequest {
    param(
        [string]$Method,
        [string]$Path,
        [AllowNull()][string]$Body,
        [string]$ContentType = 'application/json',
        [string]$BearerToken
    )

    $request = @{
        Uri = "$baseUrl$Path"
        Method = $Method
        UseBasicParsing = $true
        TimeoutSec = 30
    }
    if ($PSBoundParameters.ContainsKey('Body')) {
        $request.Body = [System.Text.Encoding]::UTF8.GetBytes($Body)
        $request.ContentType = $ContentType
    }
    if (-not [string]::IsNullOrWhiteSpace($BearerToken)) {
        $request.Headers = @{ Authorization = "Bearer $BearerToken" }
    }

    try {
        $response = Invoke-WebRequest @request
        $responseStream = $response.RawContentStream
        if ($responseStream.CanSeek) {
            $responseStream.Position = 0
        }
        $reader = [System.IO.StreamReader]::new($responseStream, [System.Text.Encoding]::UTF8, $true)
        try {
            $content = $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
        }
        return [pscustomobject]@{
            Status = [int]$response.StatusCode
            Content = $content
        }
    } catch {
        $httpResponse = $_.Exception.Response
        if ($null -eq $httpResponse) {
            throw
        }
        $reader = [System.IO.StreamReader]::new($httpResponse.GetResponseStream(), [System.Text.Encoding]::UTF8, $true)
        try {
            $content = $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
        }
        return [pscustomobject]@{
            Status = [int]$httpResponse.StatusCode
            Content = $content
        }
    }
}

function Send-MultipartRequest {
    param(
        [string]$Path,
        [AllowNull()][string]$DataPart,
        [string]$BearerToken
    )

    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Post,
        "$baseUrl$Path"
    )
    $form = [System.Net.Http.MultipartFormDataContent]::new()
    if ($null -ne $DataPart) {
        $jsonPart = [System.Net.Http.StringContent]::new(
            $DataPart,
            [System.Text.Encoding]::UTF8,
            'application/json'
        )
        $form.Add($jsonPart, 'datos', 'datos.json')
    }
    $request.Content = $form
    if (-not [string]::IsNullOrWhiteSpace($BearerToken)) {
        $request.Headers.Authorization =
            [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $BearerToken)
    }

    $response = $httpClient.SendAsync($request).GetAwaiter().GetResult()
    try {
        return [pscustomobject]@{
            Status = [int]$response.StatusCode
            Content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        }
    } finally {
        $response.Dispose()
        $request.Dispose()
    }
}

function Convert-JsonResponse {
    param([object]$Response)
    return ($Response.Content | ConvertFrom-Json)
}

function New-RandomBase64 {
    param([int]$ByteCount)
    $bytes = New-Object byte[] $ByteCount
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
        return [Convert]::ToBase64String($bytes)
    } finally {
        $generator.Dispose()
    }
}

function New-LiveCallData {
    param(
        [AllowNull()][string]$Transcription,
        [AllowNull()][object]$Latitude = -33.4,
        [AllowNull()][object]$Longitude = -70.6
    )
    return ([ordered]@{
        transcription = $Transcription
        latitudOperador = $Latitude
        longitudOperador = $Longitude
    } | ConvertTo-Json -Compress)
}

function Register-Operator {
    param([string]$Username, [string]$FirstName, [string]$Email)
    $body = [ordered]@{
        username = $Username
        password = $operatorPassword
        nombre = $FirstName
        apellido = "Prueba O'Higgins"
        correo = $Email
        institucion = 'bomberos'
    } | ConvertTo-Json -Compress
    return Send-HttpRequest -Method POST -Path '/api/auth/register' -Body $body
}

try {
    New-Item -ItemType Directory -Path $dataDirectory | Out-Null
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $serverPort = $listener.LocalEndpoint.Port
    $listener.Stop()
    $baseUrl = "http://127.0.0.1:$serverPort"

    $jwtSecret = New-RandomBase64 -ByteCount 48
    $encryptionKey = New-RandomBase64 -ByteCount 32
    $captchaSecret = New-RandomBase64 -ByteCount 48
    $env:CODES_DATA_DIR = $dataDirectory
    $env:CODES_SERVER_PORT = [string]$serverPort
    $env:CODES_SERVER_ADDRESS = '127.0.0.1'
    $env:CODES_ADMIN_USER = $adminUsername
    $env:CODES_ADMIN_PASSWORD = $adminPassword
    $env:CODES_JWT_SECRET = $jwtSecret
    $env:CODES_ENCRYPT_KEY = $encryptionKey
    $env:CODES_ALTCHA_ENABLED = 'false'
    $env:CODES_ALTCHA_SECRET = $captchaSecret
    $env:CODES_AUDIT_LOG_PATH = Join-Path $dataDirectory 'audit.log'

    $startupProcess = Start-Process -FilePath $executable -WorkingDirectory $AppImageDirectory -PassThru -WindowStyle Hidden
    $httpClient = [System.Net.Http.HttpClient]::new()

    $ready = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        try {
            $health = Invoke-WebRequest -Uri "$baseUrl/api/health" -UseBasicParsing -TimeoutSec 2
            if ($health.StatusCode -eq 200) {
                $ready = $true
                break
            }
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    if (-not $ready) {
        throw "App-image did not become ready at $baseUrl"
    }
    Assert-Condition 'packaged app-image started with isolated data' $ready

    $unauthorized = Send-HttpRequest -Method GET -Path '/api/llamadas/pending'
    Assert-Status 'protected call queue rejects missing authorization' $unauthorized 401
    $malformedJson = Send-HttpRequest -Method POST -Path '/api/auth/login' -Body '{'
    Assert-Status 'malformed JSON is rejected' $malformedJson 400
    $nullJson = Send-HttpRequest -Method POST -Path '/api/auth/login' -Body 'null'
    Assert-Status 'JSON null is rejected' $nullJson 400
    $incompleteLogin = Send-HttpRequest -Method POST -Path '/api/auth/login' -Body '{}'
    Assert-Status 'incomplete login request is rejected' $incompleteLogin 400
    $invalidLogin = Send-HttpRequest -Method POST -Path '/api/auth/login' -Body (
        @{ username = 'unknown'; password = 'invalid' } | ConvertTo-Json -Compress
    )
    Assert-Status 'invalid credentials are rejected' $invalidLogin 401

    $adminResponse = Send-HttpRequest -Method POST -Path '/api/auth/login' -Body (
        @{ username = $adminUsername; password = $adminPassword } | ConvertTo-Json -Compress
    )
    Assert-Status 'isolated bootstrap administrator can log in' $adminResponse 200
    $adminToken = (Convert-JsonResponse $adminResponse).token

    $registerNull = Send-HttpRequest -Method POST -Path '/api/auth/register' -Body 'null'
    Assert-Status 'null registration request is rejected' $registerNull 400
    $registerIncomplete = Send-HttpRequest -Method POST -Path '/api/auth/register' -Body '{}'
    Assert-Status 'incomplete registration is rejected' $registerIncomplete 400

    $testName = 'Ana ' + [char]0x00D1 + 'u' + [char]0x00F1 + 'oa ' +
        [char]::ConvertFromUtf32(0x1F600) + " O'Higgins"
    $testNameWithSurname = $testName + " Prueba O'Higgins"
    $registerResponse = Register-Operator -Username $firstUsername `
        -FirstName $testName -Email "$firstUsername@example.com"
    Assert-Status 'registration preserves accents, apostrophes, punctuation, and emoji' $registerResponse 201
    $firstAccount = Convert-JsonResponse $registerResponse
    Assert-Condition 'registered name is preserved in the response' (
        $firstAccount.user.fullName -eq $testNameWithSurname
    )
    $enableFirst = Send-HttpRequest -Method PATCH -Path "/api/users/$($firstAccount.user.id)/enable" `
        -BearerToken $adminToken
    Assert-Status 'administrator activates registered operator' $enableFirst 200
    $firstLogin = Send-HttpRequest -Method POST -Path '/api/auth/login' -Body (
        @{ username = $firstUsername; password = $operatorPassword } | ConvertTo-Json -Compress
    )
    Assert-Status 'registered operator can log in after activation' $firstLogin 200
    $firstToken = (Convert-JsonResponse $firstLogin).token

    $secondRegister = Register-Operator -Username $secondUsername `
        -FirstName 'Second Operator' -Email "$secondUsername@example.test"
    Assert-Status 'second operator registration for access-control test' $secondRegister 201
    $secondAccount = Convert-JsonResponse $secondRegister
    $enableSecond = Send-HttpRequest -Method PATCH -Path "/api/users/$($secondAccount.user.id)/enable" `
        -BearerToken $adminToken
    Assert-Status 'administrator activates second operator' $enableSecond 200
    $secondLogin = Send-HttpRequest -Method POST -Path '/api/auth/login' -Body (
        @{ username = $secondUsername; password = $operatorPassword } | ConvertTo-Json -Compress
    )
    Assert-Status 'second operator can log in after activation' $secondLogin 200
    $secondToken = (Convert-JsonResponse $secondLogin).token

    foreach ($transcription in @('', '   ', $null)) {
        $label = if ($null -eq $transcription) { 'null' } elseif ($transcription.Length -eq 0) { 'empty' } else { 'whitespace' }
        $response = Send-MultipartRequest -Path '/api/llamadas/live' `
            -DataPart (New-LiveCallData -Transcription $transcription) -BearerToken $firstToken
        Assert-Status "$label live transcription is rejected" $response 400
    }
    $longTranscript = New-LiveCallData -Transcription ('x' * 20001)
    $longResponse = Send-MultipartRequest -Path '/api/llamadas/live' -DataPart $longTranscript -BearerToken $firstToken
    Assert-Status 'oversized live transcription is rejected' $longResponse 400
    $badCoordinates = Send-MultipartRequest -Path '/api/llamadas/live' `
        -DataPart (New-LiveCallData -Transcription 'Hay un incendio en una vivienda.' -Latitude 91 -Longitude -70) `
        -BearerToken $firstToken
    Assert-Status 'latitude outside geographic range is rejected' $badCoordinates 400
    $missingPart = Send-MultipartRequest -Path '/api/llamadas/live' -DataPart $null -BearerToken $firstToken
    Assert-Status 'missing multipart request data is rejected' $missingPart 400

    $accentedCharacters = -join @(
        [char]0x00E1, [char]0x00E9, [char]0x00ED, [char]0x00F3, [char]0x00FA,
        [char]0x00C1, [char]0x00C9, [char]0x00CD, [char]0x00D3, [char]0x00DA
    )
    $specialTranscriptions = @(
        [string][char]0x0027,
        [string][char]0x0022,
        '.',
        ';',
        ':',
        [string][char]0x00F1,
        $accentedCharacters,
        [char]::ConvertFromUtf32(0x1F600),
        '1234567890',
        '!@#$%^&*()[]{}<>/\|+=-_'
    )
    $specialIndex = 0
    foreach ($specialTranscript in $specialTranscriptions) {
        $specialInputText = "Hay un incendio en una vivienda. $specialTranscript"
        $specialResponse = Send-MultipartRequest -Path '/api/llamadas/live' `
            -DataPart (New-LiveCallData -Transcription $specialInputText) -BearerToken $firstToken
        Assert-Status "special transcription input $specialIndex is accepted" $specialResponse 201
        if ($specialResponse.StatusCode -eq 201) {
            $specialCall = Convert-JsonResponse $specialResponse
            Assert-Condition "special transcription input $specialIndex is preserved" (
                $specialCall.transcription -eq $specialInputText
            )
        }
        $specialIndex++
    }

    $transcription = "Hay un incendio en una vivienda, en Av. O'Higgins, " +
        [char]0x00D1 + 'u' + [char]0x00F1 + 'oa. No hay heridos. ' +
        [char]::ConvertFromUtf32(0x1F692)
    $createResponse = Send-MultipartRequest -Path '/api/llamadas/live' `
        -DataPart (New-LiveCallData -Transcription $transcription) -BearerToken $firstToken
    Assert-Status 'valid live call with Unicode and punctuation is accepted' $createResponse 201
    $createdCall = Convert-JsonResponse $createResponse
    Assert-Condition 'call transcription retains original Unicode and punctuation' (
        $createdCall.transcription -eq $transcription
    )
    Assert-Condition 'classifier returns category and institutional recommendations' (
        $createdCall.incidentCategories.Count -gt 0 -and $createdCall.institutionRecommendations.Count -gt 0
    )

    $assignResponse = Send-HttpRequest -Method POST -Path "/api/llamadas/$($createdCall.id)/assign" `
        -BearerToken $firstToken
    Assert-Status 'operator can assign the created case' $assignResponse 200
    $noAuthUpdate = Send-HttpRequest -Method POST -Path "/api/llamadas/$($createdCall.id)/updates" `
        -Body (@{ updateId = [guid]::NewGuid().ToString(); information = 'unauthorized' } | ConvertTo-Json -Compress)
    Assert-Status 'case update rejects missing authorization' $noAuthUpdate 401
    $invalidUpdate = Send-HttpRequest -Method POST -Path "/api/llamadas/$($createdCall.id)/updates" `
        -Body '{}' -BearerToken $firstToken
    Assert-Status 'incomplete case update is rejected' $invalidUpdate 400

    $updateId = [guid]::NewGuid().ToString()
    $updateBody = @{ updateId = $updateId; information = 'Hay una persona inconsciente.' } | ConvertTo-Json -Compress
    $firstUpdate = Send-HttpRequest -Method POST -Path "/api/llamadas/$($createdCall.id)/updates" `
        -Body $updateBody -BearerToken $firstToken
    Assert-Status 'assigned operator can update the case' $firstUpdate 200
    $updateResult = Convert-JsonResponse $firstUpdate
    Assert-Condition 'first update is persisted on the same case' (
        $updateResult.id -eq $createdCall.id -and $updateResult.informationUpdates.Count -eq 1
    )
    $duplicateUpdate = Send-HttpRequest -Method POST -Path "/api/llamadas/$($createdCall.id)/updates" `
        -Body $updateBody -BearerToken $firstToken
    Assert-Status 'duplicate update request is accepted idempotently' $duplicateUpdate 200
    $duplicateResult = Convert-JsonResponse $duplicateUpdate
    Assert-Condition 'duplicate update does not create another history entry' (
        $duplicateResult.informationUpdates.Count -eq 1
    )
    $unassignedUpdate = Send-HttpRequest -Method POST -Path "/api/llamadas/$($createdCall.id)/updates" `
        -Body (@{ updateId = [guid]::NewGuid().ToString(); information = 'Cambio por operador no asignado.' } | ConvertTo-Json -Compress) `
        -BearerToken $secondToken
    Assert-Status 'operator not assigned to the case is forbidden from updating it' $unassignedUpdate 403

    $readBack = Send-HttpRequest -Method GET -Path "/api/llamadas/$($createdCall.id)" -BearerToken $firstToken
    Assert-Status 'created and updated case can be read back' $readBack 200
    $readBackCall = Convert-JsonResponse $readBack
    Assert-Condition 'case and update persist on the same ID' (
        $readBackCall.id -eq $createdCall.id -and $readBackCall.informationUpdates.Count -eq 1
    )
    $config = Convert-JsonResponse (Send-HttpRequest -Method GET -Path '/api/public-config')
    Assert-Condition 'Groq Whisper provider remains the configured ASR identity' (
        $config.transcriptionProvider -eq 'groq-whisper'
    )
    Write-Host "Groq Whisper enabled: $($config.transcriptionConfigured). No audio was sent to an external provider."

    Write-Host "HTTP validation results: $script:passed passed, $script:failed failed."
} finally {
    if ($httpClient) {
        $httpClient.Dispose()
    }
    if ($startupProcess) {
        $ownedProcessIds = New-Object 'System.Collections.Generic.HashSet[int]'
        [void]$ownedProcessIds.Add([int]$startupProcess.Id)
        $processes = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)
        $changed = $true
        while ($changed) {
            $changed = $false
            foreach ($process in $processes) {
                if ($ownedProcessIds.Contains([int]$process.ParentProcessId) `
                        -and -not $ownedProcessIds.Contains([int]$process.ProcessId)) {
                    [void]$ownedProcessIds.Add([int]$process.ProcessId)
                    $changed = $true
                }
            }
        }
        foreach ($processId in @($ownedProcessIds | Sort-Object -Descending)) {
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        }
        Start-Sleep -Milliseconds 500
    }
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    if (Test-Path -LiteralPath $dataDirectory) {
        Remove-Item -LiteralPath $dataDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
}
