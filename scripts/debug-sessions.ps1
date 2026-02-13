# Script de diagnóstico para ver todas las sesiones multimedia activas

Add-Type -AssemblyName System.Runtime.WindowsRuntime

# Helper function para convertir IAsyncOperation a Task
function Await-Task {
    param (
        [Parameter(Mandatory)]
        $AsyncOperation
    )
    
    $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | 
        Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 } | 
        Select-Object -First 1
    
    $task = $asTask.MakeGenericMethod($AsyncOperation.GetType().GenericTypeArguments[0]).Invoke($null, @($AsyncOperation))
    $task.Wait()
    return $task.Result
}

$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]

try {
    $sessionManagerAsync = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()
    $sessionManager = Await-Task -AsyncOperation $sessionManagerAsync
    
    $sessions = $sessionManager.GetSessions()
    
    Write-Host "Total sessions found: $($sessions.Count)"
    Write-Host ""
    
    if ($sessions.Count -eq 0) {
        Write-Host "No active media sessions found."
        Write-Host ""
        Write-Host "Possible reasons:"
        Write-Host "1. No media is currently playing"
        Write-Host "2. The browser doesn't expose media controls to Windows"
        Write-Host "3. Try using Edge browser (better GSMTC support)"
        Write-Host "4. Try Spotify desktop app"
    } else {
        foreach ($session in $sessions) {
            $mediaPropertiesAsync = $session.TryGetMediaPropertiesAsync()
            $mediaProperties = Await-Task -AsyncOperation $mediaPropertiesAsync
            
            $playbackInfo = $session.GetPlaybackInfo()
            $playbackStatus = $playbackInfo.PlaybackStatus.ToString()
            
            Write-Host "App ID: $($session.SourceAppUserModelId)"
            Write-Host "Artist: $($mediaProperties.Artist)"
            Write-Host "Title: $($mediaProperties.Title)"
            Write-Host "Album: $($mediaProperties.AlbumTitle)"
            Write-Host "Status: $playbackStatus"
            Write-Host "---"
        }
    }
} catch {
    Write-Host "Error: $_"
}

