param(
    [string]$SourceDatabase = "kk_manager_task_dashboard",
    [string]$TargetDatabase = "kk_manager_bgmn_doc_v1",
    [string]$HostName = "127.0.0.1",
    [int]$Port = 3306,
    [string]$UserName = "root",
    [string]$Password = ""
)

$ErrorActionPreference = "Stop"

foreach ($databaseName in @($SourceDatabase, $TargetDatabase)) {
    if ($databaseName -notmatch '^[A-Za-z0-9_]+$') {
        throw "数据库名只能包含字母、数字和下划线：$databaseName"
    }
}

$mysql = Get-Command mysql -ErrorAction SilentlyContinue
$mysqldump = Get-Command mysqldump -ErrorAction SilentlyContinue
if (-not $mysql -or -not $mysqldump) {
    throw "未找到 mysql/mysqldump，请先安装 MySQL 客户端并加入 PATH。"
}

$previousPassword = $env:MYSQL_PWD
try {
    if ($Password) { $env:MYSQL_PWD = $Password }
    & $mysql.Source --host=$HostName --port=$Port --user=$UserName --execute="CREATE DATABASE IF NOT EXISTS ``$TargetDatabase`` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
    if ($LASTEXITCODE -ne 0) { throw "创建分支数据库失败。" }

    & $mysqldump.Source --host=$HostName --port=$Port --user=$UserName --single-transaction --routines --triggers --events $SourceDatabase |
        & $mysql.Source --host=$HostName --port=$Port --user=$UserName $TargetDatabase
    if ($LASTEXITCODE -ne 0) { throw "复制数据库失败。" }

    $migration = Join-Path (Split-Path $PSScriptRoot -Parent) "sql\upgrade_project_resources.sql"
    Get-Content -Raw -LiteralPath $migration |
        & $mysql.Source --host=$HostName --port=$Port --user=$UserName $TargetDatabase
    if ($LASTEXITCODE -ne 0) { throw "执行分支数据库迁移失败。" }

    Write-Host "已创建、复制并迁移分支数据库：$TargetDatabase"
}
finally {
    $env:MYSQL_PWD = $previousPassword
}
