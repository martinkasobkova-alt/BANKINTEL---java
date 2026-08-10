param(
    [string]$OutputPath = "backend-java/src/main/resources/data/ecb_cbd2_item_labels.json"
)

$ErrorActionPreference = "Stop"
$structureUrl = "https://data-api.ecb.europa.eu/service/datastructure/ECB/ECB_CBD2/1.0?references=all"
$temporaryFile = [System.IO.Path]::GetTempFileName()

try {
    Invoke-WebRequest `
        -Uri $structureUrl `
        -OutFile $temporaryFile `
        -Headers @{ Accept = "application/vnd.sdmx.structure+xml;version=2.1" } `
        -TimeoutSec 120

    [xml]$structure = Get-Content -LiteralPath $temporaryFile
    $namespaces = [System.Xml.XmlNamespaceManager]::new($structure.NameTable)
    $namespaces.AddNamespace("str", "http://www.sdmx.org/resources/sdmxml/schemas/v2_1/structure")
    $namespaces.AddNamespace("com", "http://www.sdmx.org/resources/sdmxml/schemas/v2_1/common")

    $codelist = $structure.SelectSingleNode('//str:Codelist[@id="CL_CB_ITEM"]', $namespaces)
    if ($null -eq $codelist) {
        throw "ECB CBD2 codelist CL_CB_ITEM was not found."
    }

    $labels = [ordered]@{}
    foreach ($code in $codelist.SelectNodes("str:Code", $namespaces)) {
        $name = $code.SelectSingleNode('com:Name[@xml:lang="en"]', $namespaces)
        if ($null -eq $name) {
            $name = $code.SelectSingleNode("com:Name", $namespaces)
        }
        if ($null -ne $name -and -not [string]::IsNullOrWhiteSpace($name.InnerText)) {
            $labels[$code.id] = $name.InnerText.Trim()
        }
    }

    $payload = [ordered]@{
        schema_version = 1
        flow = "CBD2"
        dimension = "CB_ITEM"
        source_url = $structureUrl
        labels = $labels
    }
    $parent = Split-Path -Parent $OutputPath
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $json = $payload | ConvertTo-Json -Depth 4
    [System.IO.File]::WriteAllText(
        [System.IO.Path]::GetFullPath($OutputPath),
        $json,
        [System.Text.UTF8Encoding]::new($false)
    )
    Write-Output "Wrote $($labels.Count) ECB CBD2 item labels to $OutputPath"
}
finally {
    Remove-Item -LiteralPath $temporaryFile -Force -ErrorAction SilentlyContinue
}
