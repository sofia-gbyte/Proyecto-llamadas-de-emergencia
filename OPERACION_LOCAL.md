# Operacion local de CODES

Guia de instalacion, respaldo, seguridad y preparacion para una futura instalacion en LAN. CODES sigue funcionando localmente y no requiere dominio, HTTPS publico ni una base de datos externa.

## Inicio en Windows

Para desarrollo y llamadas en vivo, el flujo basico es:

```text
CODES.bat -> 1. Iniciar CODES
```

Ese modo busca o instala Java 21 y Maven, carga `.env.maps` si existe, actualiza el diccionario de calles cuando corresponde, inicia Spring Boot y trata de iniciar el ASR local. Docker no es requisito para ejecutar el backend; solo se necesita para los mapas autohospedados.

La aplicacion queda disponible en `http://localhost:8000` y el ASR local en `ws://localhost:6006`.

## Mapa y servicios cartograficos

Leaflet se sirve desde el propio JAR. Se incluye `compose.maps.yaml` para ejecutar servicios propios de teselas, geocodificacion y rutas. El modo predeterminado de CODES aun consulta servicios publicos; los pasos de abajo cambian el entorno local a los servicios autohospedados.

### Docker Desktop en Windows

En `CODES.bat`, elige **2. Instalar o iniciar Docker Desktop**. El instalador solicita confirmacion, usa `winget` en modo por usuario y no acepta el acuerdo de licencia automaticamente. Si `winget` o el modo por usuario no estan disponibles, instala Docker Desktop manualmente desde la [pagina oficial para Windows](https://docs.docker.com/desktop/setup/install/windows-install/).

Docker Desktop no tiene una licencia abierta universal. Docker lo ofrece sin costo para uso personal, educativo individual y proyectos open source no comerciales. Para una empresa, los limites publicados requieren tener menos de 250 empleados **y** menos de USD 10 millones de ingresos anuales; fuera de esas condiciones y para entidades gubernamentales se requiere un plan pago. El uso educativo coordinado por una institucion puede tener condiciones distintas. Confirma el caso real en el [acuerdo de Docker](https://www.docker.com/legal/docker-subscription-service-agreement/) antes de usarlo.

Para produccion en un servidor Linux, instala Docker Engine y el plugin Compose directamente en Linux: ambos proyectos son Apache-2.0 y no requieren Docker Desktop. Docker Desktop en Windows sirve para desarrollo/local; no es el runtime recomendado para publicar el servicio web.

### Arranque local en Windows

Requiere Docker Desktop iniciado, contenedores Linux y Docker Compose. Descarga un extracto regional `.osm.pbf` de OpenStreetMap, por ejemplo desde Geofabrik, y dejalo en `map-data/chile-latest.osm.pbf`. Un extracto mas pequeno reduce bastante el tiempo y disco de importacion; CODES debe tener el centro y radio configurados dentro de la cobertura importada.

```powershell
New-Item -ItemType Directory -Force .\map-data
if (-not (Test-Path .env.maps)) { Copy-Item .env.maps.example .env.maps }
```

La opcion Docker crea `.env.maps` desde la plantilla si aun no existe. Edita ese archivo y cambia `MAPS_NOMINATIM_PASSWORD` por una clave larga. Las lineas `CODES_*` vienen comentadas para que CODES siga usando sus defaults hasta que el stack este listo. Importa las teselas y prepara la red de rutas. La primera importacion de Nominatim ocurre al iniciar y puede tardar bastante; deja el proceso terminar.

```powershell
docker compose --env-file .env.maps -f compose.maps.yaml --profile tools run --rm --no-deps tile-import
docker compose --env-file .env.maps -f compose.maps.yaml --profile maps run --rm --no-deps --entrypoint osrm-extract osrm -p /opt/car.lua /data/chile-latest.osm.pbf
docker compose --env-file .env.maps -f compose.maps.yaml --profile maps run --rm --no-deps --entrypoint osrm-partition osrm /data/chile-latest.osrm
docker compose --env-file .env.maps -f compose.maps.yaml --profile maps run --rm --no-deps --entrypoint osrm-customize osrm /data/chile-latest.osrm
docker compose --env-file .env.maps -f compose.maps.yaml --profile maps up -d
docker compose --env-file .env.maps -f compose.maps.yaml --profile maps logs -f nominatim
```

Una vez terminada la importacion y cuando los servicios respondan, descomenta las lineas `CODES_*` de `.env.maps` para que `CODES.bat` las cargue automaticamente. Photon queda deshabilitado con el valor `disabled`; puedes sustituirlo por una instancia propia si la necesitas.

Comprueba Nominatim en `http://127.0.0.1:8082/search?q=Providencia%2C%20Chile&format=json`, rutas en `http://127.0.0.1:5000/route/v1/driving/-70.6693,-33.4489;-70.65,-33.44?overview=false` y teselas en `http://127.0.0.1:8081/tile/0/0/0.png`.

Los tres puertos se publican solo en loopback. Para conectar otros dispositivos, no los abras directamente: usa Caddy/Nginx con HTTPS y rutas publicas para teselas/rutas. La API Nominatim puede quedarse privada para que solo la consulte el backend. En ese escenario cambia `CODES_MAP_TILES_URL` y `CODES_ROUTING_URL` por las URLs HTTPS del proxy; `localhost` desde un telefono significa el propio telefono, no el servidor CODES.

Las imagenes de contenedor y el extracto PBF se descargan durante preparacion. Despues de importarlos, las consultas y el mapa operan en tus equipos, sin llamar a los servidores publicos OSM/Nominatim/OSRM. El stack no actualiza los datos automaticamente: fija un calendario, descarga un mismo corte PBF para los tres servicios, prepara OSRM de nuevo, planifica la reimportacion de teselas/Nominatim y respalda los volumenes antes de actualizar. El coste pasa a ser CPU, RAM, disco, red, energia y mantenimiento, no una tarifa por solicitud.

OpenStreetMap permite usar los datos bajo ODbL con atribucion, pero eso no convierte sus servidores publicos de teselas/geocodificacion en una API gratuita de produccion. Ver [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) para las obligaciones de datos y licencias de los servicios autohospedados.

El diccionario `data/calles_chile.txt` solo ayuda a corregir nombres de calles; no contiene coordenadas y no sustituye un geocodificador ni las teselas del mapa.

## Respaldo y restauracion

CODES.bat permite crear un respaldo local. Tambien se puede ejecutar:

```powershell
.\tools\backup_codes.ps1 -IncluirLogs
.\tools\restore_codes.ps1 -Respaldo .\backups\codes_YYYYMMDD_HHMMSS
```

CODES debe estar detenido para respaldar o restaurar la base H2. El respaldo incluye base de datos, audios, cache, diccionario, logs y secretos. Es informacion sensible y debe protegerse.

La perdida de `CODES_ENCRYPT_KEY` impide recuperar audios cifrados.

## Seguridad y evidencia

La aplicacion implementa JWT, BCrypt, roles, aislamiento institucional, invalidacion de sesiones, CORS restringido, limites de intentos, auditoria y cifrado AES-256-GCM. Turnstile es opcional y viene desactivado; si se expone a Internet, debe habilitarse o sustituirse por protecciones anti-abuso equivalentes en el proxy/WAF.

Antes de una entrega o instalacion operativa se debe comprobar:

- Peticion sin JWT: responde `401` o `403`.
- Usuario desactivado: no puede usar su JWT anterior.
- Operador: solo ve casos propios o instituciones sugeridas.
- Operador: no cierra casos ajenos o no asignados.
- Supervisor: puede cerrar casos de su institucion.
- Administrador: puede supervisar todas las instituciones.
- Logout, cambio de clave y cierre forzado invalidan JWT anteriores.
- Audio con tipo o extension no permitidos: se rechaza.
- Eventos operativos: quedan en `logs\auditoria.log`.

El flujo principal que debe demostrarse es:

```text
login -> llamada -> ASR o transcripcion manual -> prioridad -> direccion
-> geocodificacion -> instituciones sugeridas -> sin asignar -> en curso -> cerrada
```

## Diagnostico

El estado publico minimo es:

```text
GET /api/health
```

El diagnostico detallado para administradores es:

```text
GET  /api/admin/status
POST /api/admin/selftest
```

El autochequeo revisa base de datos, disco, espacio libre, ASR y cache de geocodificacion. ASR y geocodificacion pueden quedar en modo degradado sin impedir la operacion manual.

## Limites actuales

CODES distribuye alertas simultaneamente dentro de sus colas locales segun las instituciones sugeridas. Todavia no envia alertas a sistemas externos de Carabineros, SAMU o Bomberos.

## Siguiente etapa: web real y dispositivos

La version actual no debe publicarse directamente en Internet. Para habilitar acceso desde telefonos o computadores de otras redes, seguir este orden:

1. Definir responsable operativo, jurisdiccion, retencion de audios/transcripciones, consentimiento, control de acceso y procedimiento ante incidentes. No usar datos reales hasta acordar estas reglas.
2. Preparar un servidor Linux o Windows con dominio, DNS, firewall y almacenamiento cifrado. Mantener la aplicacion y H2 en almacenamiento persistente; para varias instancias o alta disponibilidad, migrar a PostgreSQL y agregar migraciones versionadas.
3. Colocar Caddy o Nginx delante de Spring Boot, emitir HTTPS y reenviar solo el trafico web necesario. Mantener el puerto 8000 y el ASR fuera de Internet; permitir acceso a Spring Boot solo desde el proxy.
4. Probar login, captura de microfono y geolocalizacion desde los dispositivos reales. Los navegadores requieren HTTPS (salvo `localhost`) para microfono y geolocalizacion.
5. El ASR actual escucha localmente y su WebSocket no tiene autenticacion. No publicarlo ni reenviarlo a Internet todavia. Antes de dispositivos remotos, implementar autenticacion de corta duracion, limites de conexiones y WSS en el proxy/servicio; entonces configurar `CODES_ASR_WEBSOCKET_URL=wss://...`.
6. Desplegar `compose.maps.yaml` con un extracto regional OSM, configurar el proxy HTTPS para teselas/rutas y apuntar Nominatim desde Spring Boot a su direccion privada. Photon es opcional: deja `CODES_PHOTON_BASE_URL` vacio para no consultarlo. Definir actualizaciones coordinadas del PBF, respaldo de volumenes y atribucion/licencias.
7. Configurar secretos fuera del repositorio, CORS con el origen HTTPS exacto, Turnstile o proteccion anti-abuso equivalente, SMTP propio si se requiere recuperacion de clave y monitorizacion/alertas.
8. Automatizar respaldos cifrados de BD, audios, claves y datos cartograficos, y ensayar restauracion. Definir retencion y borrado antes de guardar llamadas reales.
9. Hacer pruebas de seguridad, concurrencia, navegadores y perdida de red; ejecutar un piloto cerrado con operadores antes de integrar o comunicar alertas a servicios oficiales.

La solucion actual no integra despachos oficiales y el clasificador automatico no reemplaza el criterio de un operador. HTTPS/WSS, autenticacion del ASR, PostgreSQL, migraciones, backups programados, monitoreo e integraciones oficiales siguen siendo trabajo de produccion.
