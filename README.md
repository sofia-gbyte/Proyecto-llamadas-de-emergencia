# CODES

Sistema de gestión y clasificación de llamadas de emergencia con ASR local, autenticación JWT, geolocalización y panel operativo web.

CODES es una aplicación Spring Boot para registrar llamadas, detectar prioridad, extraer dirección, cifrar audios y operar con una interfaz de despacho centralizada.

## ¿Qué incluye?

- Frontend web con mapa, dashboard y flujo de llamadas
- API REST con Spring Boot y Spring Security
- Autenticación por JWT + BCrypt
- Clasificación heurística de emergencias por texto
- Geocodificación y coordenadas para incidentes
- ASR local con sherpa-onnx para transcripción en tiempo real o por archivo
- Cifrado AES-256-GCM para audios almacenados
- Base de datos H2 local para entorno de desarrollo

## Stack principal

- Java 21
- Spring Boot 3
- Spring Security
- Spring Data JPA
- H2 Database
- Leaflet + Esri World Imagery
- sherpa-onnx ASR

## Requisitos

- Java 21
- Maven
- Windows recomendado para la parte de ASR local

## Inicio rápido

```powershell
$env:CODES_JWT_SECRET="genera-un-secreto-largo-y-aleatorio"
$env:CODES_ENCRYPT_KEY="genera-una-clave-base64-de-32-bytes"
$env:CODES_ADMIN_USER="admin"
$env:CODES_ADMIN_PASSWORD="una-password-segura"

mvn spring-boot:run
```

Luego abre:

- http://localhost:8000

## ASR local

La transcripción se realiza con `sherpa-onnx` y el flujo local se prepara con:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\tools\asr\install_windows.ps1
.\tools\asr\start_asr_windows.ps1
```

El directorio `tools/asr` contiene el script de instalación y arranque del modelo ASR.

## Corrección de calles chilenas

El ASR se mantiene sin cambios. Después de transcribir, CODES corrige nombres de calles
con coincidencia fonética y difusa contra un diccionario de OpenStreetMap. El archivo es
opcional: si no existe, se conserva el texto original.

Para generarlo en Windows:

```powershell
python -m pip install osmium
Invoke-WebRequest https://download.geofabrik.de/south-america/chile-latest.osm.pbf -OutFile chile-latest.osm.pbf
python tools/streets/extract_calles_chile.py chile-latest.osm.pbf data/calles_chile.txt
```

El archivo PBF puede borrarse después de la extracción. La aplicación lo carga desde
`app.calles-diccionario` al iniciar.

## Estructura relevante

```text
CODES/
├── src/main/java/cl/codes
├── src/main/resources/static
├── src/main/resources/application.properties
├── tools/asr/
├── data/
├── pom.xml
├── README.md
├── INICIAR_CODES.bat
└── .gitignore
```

## Nota

Este repositorio está orientado al proyecto CODES actual y no incluye versiones antiguas o repos legacy no mantenidas.

## Licencia

Proyecto en desarrollo para uso interno / académico / operativo, según alcance del equipo responsable.

