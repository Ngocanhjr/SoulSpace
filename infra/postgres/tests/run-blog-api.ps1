$ErrorActionPreference = 'Stop'
$containerName = 'soulspace-blog-test-' + [Guid]::NewGuid().ToString('N')
$adminPassword = [Guid]::NewGuid().ToString('N')
$runtimePassword = [Guid]::NewGuid().ToString('N')
$migratorPassword = [Guid]::NewGuid().ToString('N')
$testLabel = 'soulspace.agent-test=blog-api'
$readyAttempts = 30
$originalLocation = Get-Location
$exitCode = 1

try {
    Set-Location (Join-Path $PSScriptRoot '../../..')
    docker run --detach --name $containerName --label $testLabel --publish '127.0.0.1::5432' `
        --env "POSTGRES_PASSWORD=$adminPassword" --env "DB_PASSWORD=$runtimePassword" `
        --env "FLYWAY_PASSWORD=$migratorPassword" postgres:17.11-bookworm | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create the isolated PostgreSQL container.' }
    $ready = $false
    for ($attempt = 0; $attempt -lt $readyAttempts; $attempt++) {
        docker exec $containerName pg_isready -U postgres *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (!$ready) { throw 'PostgreSQL did not become ready.' }
    Get-Content 'infra/postgres/provision.sql' -Raw | docker exec --interactive $containerName psql -U postgres -d postgres
    if ($LASTEXITCODE -ne 0) { throw 'Provisioning failed.' }
    $publishedPort = docker port $containerName 5432/tcp
    $port = ($publishedPort.Trim() -split ':')[-1]
    if ($port -notmatch '^\d+$') { throw 'Cannot resolve the isolated database port.' }
    $env:SOULSPACE_BLOG_TEST_DB = 'true'
    $env:BLOG_TEST_DB_URL = "jdbc:postgresql://127.0.0.1:$port/postgres"
    $env:BLOG_TEST_RUNTIME_PASSWORD = $runtimePassword
    $env:BLOG_TEST_MIGRATOR_PASSWORD = $migratorPassword
    Set-Location 'soulspace'
    mvn.cmd -B '-Dtest=LocalAuthenticationTests,BlogApiTests,InteractionTransactionTests,ProfileApiTests' test
    $exitCode = $LASTEXITCODE
} finally {
    Set-Location $originalLocation
    Remove-Item Env:SOULSPACE_BLOG_TEST_DB, Env:BLOG_TEST_DB_URL, Env:BLOG_TEST_RUNTIME_PASSWORD, Env:BLOG_TEST_MIGRATOR_PASSWORD -ErrorAction SilentlyContinue
    $label = docker inspect --format '{{index .Config.Labels "soulspace.agent-test"}}' $containerName 2>$null
    if ($label -eq 'blog-api' -and $containerName -match '^soulspace-blog-test-[a-f0-9]{32}$') {
        docker rm --force --volumes $containerName | Out-Null
    }
}
exit $exitCode
