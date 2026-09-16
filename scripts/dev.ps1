param(
  [switch]$WithApi
)

$compose = Join-Path $PSScriptRoot "..\infrastructure\docker-compose.yml"
if ($WithApi) {
  docker compose -f $compose --profile full up -d
} else {
  docker compose -f $compose up -d postgres
}
