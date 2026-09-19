# App Policial — CODES

Centro de Operaciones y Despacho de Emergencias (CODES), construido como una aplicación Spring Boot + H2 + frontend estático.

## Arquitectura actual

```text
Navegador (CODES)
   │
   ├── REST + JWT ────────────────► Spring Boot :8000
   │                                  │
   │                                  ├── H2 / casos / usuarios
   │                                  ├── Clasificador
   │                                  ├── Geocoder
   │                                  └── AES-256-GCM para audios
   │
   └── PCM Float32 por WebSocket ─► sherpa-onnx :6006 (localhost)
                                      │
                                      └── ASR streaming local
```

### Flujo de una llamada en vivo

1. El operador inicia **🎙 Llamada en vivo** desde CODES.
2. El navegador captura el micrófono y lo convierte a PCM mono 16 kHz.
3. Los fragmentos viajan por WebSocket a `sherpa-onnx` en `localhost:6006`.
4. La transcripción se actualiza mientras la persona habla.
5. Al detener la llamada, el frontend envía la transcripción y el audio grabado a Spring Boot.
6. Spring Boot clasifica la emergencia, extrae dirección, geocodifica, cifra el audio y crea la llamada en H2.
7. El caso aparece en **Sin asignar** y puede ser tomado por un operador.

La ruta de ASR es local y gratuita en el sentido de que no usa una API de pago por minuto. El costo real es el CPU/RAM/GPU del equipo que ejecuta el modelo.

## ASR: sherpa-onnx

Se eliminó la dependencia de la implementación Python de OpenAI Whisper como ruta principal. Para streaming usamos el modelo `sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11`, que soporta español y otros idiomas.

El modelo no se incluye en el ZIP porque pesa más de 1 GB. Se descarga con:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\tools\asr\install_windows.ps1
```

Después se inicia el WebSocket ASR con:

```powershell
.\tools\asr\start_asr_windows.ps1
```

La documentación oficial de sherpa-onnx describe el servidor WebSocket de streaming, el uso desde navegador y el procesamiento local. El modelo Nemotron 3.5 streaming ofrece español `es-US`/`es-ES` y variantes de chunk de 80, 160, 560 y 1120 ms; CODES queda configurado inicialmente en 560 ms como equilibrio entre latencia y precisión.

## Arranque de Spring Boot

Requisitos:

- Java 21
- Maven
- Python + pip solo para instalar/ejecutar sherpa-onnx en Windows

En PowerShell:

```powershell
$env:APP_POLICIAL_JWT_SECRET="pon-aqui-un-secreto-largo-y-aleatorio"
$env:APP_POLICIAL_ENCRYPT_KEY="pon-aqui-una-clave-de-32-bytes-base64"
$env:APP_POLICIAL_ADMIN_USER="admin"
$env:APP_POLICIAL_ADMIN_PASSWORD="una-clave-admin-de-10-o-mas-caracteres"

mvn spring-boot:run
```

Abrir **http://localhost:8000**.

## Primer uso de ASR

En otra terminal:

```powershell
.\tools\asr\install_windows.ps1
.\tools\asr\start_asr_windows.ps1
```

Luego inicia sesión y usa **🎙 Llamada en vivo**.

## Carpeta de datos

- `data/audios_crudos/`: audios que lleguen por el monitor de carpeta.
- `data/audios_encriptados/`: audios cifrados con AES-256-GCM.
- `data/llamadas.mv.db`: base H2 local.
- `tools/asr/models/`: modelo ASR descargado localmente; no se versiona.

## Seguridad y límites actuales

- JWT stateless y BCrypt para usuarios.
- El frontend mantiene el token solo en memoria.
- La API revalida que el usuario siga activo y su rol siga vigente.
- El WebSocket ASR de desarrollo escucha en localhost. Para producción debe ponerse detrás de TLS y autenticación.
- La clasificación actual sigue siendo heurística basada en palabras/patrones del proyecto; no es un sistema médico ni un sustituto de un operador humano.
- El geocoder usa Nominatim/OSM con caché local.

## Verificación

El frontend se validó sintácticamente con Node (`node --check`) y el HTML se pudo parsear. En el entorno de construcción usado para generar este ZIP no estaba instalado Maven, por lo que no se pudo ejecutar `mvn clean compile` aquí.


## Arranque fácil en Windows

Puedes ejecutar `INICIAR_CODES.bat`. El launcher agrega Maven a la sesión, conserva secretos locales para no romper el cifrado entre reinicios y Spring Boot arranca/detiene automáticamente el servidor ASR de Sherpa en el puerto 6006.

## Geolocalización tolerante

La geocodificación usa OpenStreetMap/Nominatim y Photon como buscador de candidatos. La consulta puede aprovechar la comuna/territorio mencionado en la llamada y seleccionar coincidencias aproximadas, lo que ayuda con errores normales del ASR en nombres de calles. La transcripción original no se modifica.
