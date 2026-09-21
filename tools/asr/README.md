# ASR local en tiempo real — sherpa-onnx

CODES usa `sherpa-onnx` para transcripción local y streaming. El navegador captura el micrófono y envía PCM `float32` por WebSocket al servidor ASR local en `localhost:6006`.

## Modelo recomendado

El proyecto está preparado para `sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11`.

Es un modelo streaming multilingüe que incluye español (`es-US` y `es-ES`). El tamaño de chunk de 560 ms es un compromiso entre latencia y precisión; sherpa-onnx también publica variantes de 80/160/1120 ms.

## Windows — instalación rápida

Desde la raíz `AppPolicialJava`:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\tools\asr\install_windows.ps1
.\tools\asr\start_asr_windows.ps1
```

La primera instalación descarga el modelo (~1.3 GB descomprimido). No se guarda dentro del ZIP del proyecto para no inflarlo.

## Uso

1. Ejecuta Spring Boot en `http://localhost:8000`.
2. Ejecuta `start_asr_windows.ps1` en otra terminal.
3. Inicia sesión en CODES.
4. Pulsa **🎙 Llamada en vivo**.
5. Autoriza el micrófono.
6. La transcripción aparece mientras hablas.
7. Al detener la llamada, CODES clasifica, geocodifica, cifra el audio y guarda el caso en H2.

Todo el ASR se procesa localmente; no se envía audio a una API de pago.

## Importante

- El navegador debe abrir CODES desde `http://localhost:8000`. Esto es un requisito práctico de acceso al micrófono y del servidor WebSocket sin certificado.
- Si el servidor ASR no está activo, el modo de llamada en vivo mostrará un error y no perderá la sesión de CODES.
- Para producción, el WebSocket debe protegerse con TLS y autenticación, y el servicio ASR debe ejecutarse como proceso supervisado.
