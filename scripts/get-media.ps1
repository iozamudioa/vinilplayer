# Script para obtener metadata multimedia de Windows usando GSMTC
# Devuelve JSON: {"artist":"...","title":"...","status":"..."}

Add-Type -AssemblyName System.Runtime.WindowsRuntime

# Cargar tipos necesarios de WinRT
[void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
[void][Windows.Media.Control.GlobalSystemMediaTransportControlsSession, Windows.Media.Control, ContentType = WindowsRuntime]

# Helper para esperar async operations
function Wait-AsyncOperation {
    param($AsyncOp)
    
    $asTaskMethod = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and 
        $_.GetParameters().Count -eq 1 -and
        $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    })[0]
    
    if ($null -eq $asTaskMethod) {
        throw "AsTask method not found"
    }
    
    $task = $asTaskMethod.MakeGenericMethod($AsyncOp.GetType().GetGenericArguments()[0]).Invoke($null, @($AsyncOp))
    
    return $task.GetAwaiter().GetResult()
}

try {
    # Obtener el session manager
    $sessionManagerOp = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()
    $sessionManager = Wait-AsyncOperation -AsyncOp $sessionManagerOp
    
    if ($null -eq $sessionManager) {
        Write-Output '{"artist":"","title":"","status":"STOPPED"}'
        exit 0
    }
    
    # Intentar obtener todas las sesiones primero
    $sessions = $sessionManager.GetSessions()
    
    if ($sessions.Count -eq 0) {
        Write-Output '{"artist":"","title":"","status":"STOPPED"}'
        exit 0
    }
    
    # Usar la primera sesión activa
    $session = $sessions[0]
    
    if ($null -eq $session) {
        Write-Output '{"artist":"","title":"","status":"STOPPED"}'
        exit 0
    }
    
    # Obtener metadata
    $mediaPropertiesOp = $session.TryGetMediaPropertiesAsync()
    $mediaProperties = Wait-AsyncOperation -AsyncOp $mediaPropertiesOp
    
    # Obtener estado de reproducción
    $playbackInfo = $session.GetPlaybackInfo()
    $playbackStatus = $playbackInfo.PlaybackStatus.ToString()
    
    # Extraer artista y título
    $artist = if ($mediaProperties.Artist) { $mediaProperties.Artist } else { "" }
    $title = if ($mediaProperties.Title) { $mediaProperties.Title } else { "" }
    
    # Mapear estado
    $status = switch ($playbackStatus) {
        "Playing" { "PLAYING" }
        "Paused" { "PAUSED" }
        default { "STOPPED" }
    }
    
    # Escapar comillas en JSON
    $artist = $artist -replace '"', '\"'
    $title = $title -replace '"', '\"'
    
    # Generar JSON manualmente para evitar problemas de encoding
    $json = "{`"artist`":`"$artist`",`"title`":`"$title`",`"status`":`"$status`"}"
    
    Write-Output $json
    
} catch {
    # Error: devolver vacío
    Write-Output '{"artist":"","title":"","status":"STOPPED"}'
}

