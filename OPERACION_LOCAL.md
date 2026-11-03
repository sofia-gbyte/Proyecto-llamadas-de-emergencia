# Operacion local de CODES

Guía de instalación, respaldo, seguridad y preparación para una futura instalación en LAN. Esta guía corresponde a CODES `1.0.2` (versión del artefacto definida en `pom.xml`). La versión se orienta a uso local o en una red interna controlada; no está preparada para exposición directa a Internet ni para despacho de emergencias reales sin las revisiones indicadas aquí.

Consulta [README.md](README.md) para la arquitectura, los contratos de la API y las decisiones técnicas, y [CHANGELOG.md](CHANGELOG.md) para los cambios de esta versión.

## Inicio en Windows

Para desarrollo y llamadas en vivo, el flujo basico es:

```text
CODES.bat -> 1. Iniciar CODES
```

Ese modo busca o instala Java 21 y Maven, carga las claves locales e inicia Spring Boot. No ejecuta scripts Python ni consulta Overpass.

La aplicacion queda disponible en `http://localhost:8000`. ALTCHA protege el login y el registro sin consultar servicios externos; el lanzador genera y guarda su clave HMAC local.

### Claves de los proveedores

Edita `data\.codes-secrets.ps1` y agrega tus claves:

```powershell
$env:GROQ_API_KEY = 'tu_clave_de_groq'
$env:GEOAPIFY_API_KEY = 'tu_clave_de_geoapify'
```

Reinicia `CODES.bat` para cargarlas. El archivo está excluido de Git; no compartas ni publiques sus valores. Groq recibe el audio al crear una llamada y Geoapify recibe la dirección consultada.

La plantilla de nombres de variables está en `codes-secrets.example.ps1`, en la raíz del proyecto. No contiene credenciales válidas: reemplaza sus marcadores localmente antes de usarla. En una instalación nueva, `CODES.bat` puede generar automáticamente `data\.codes-secrets.ps1`.

No reemplaces automáticamente el archivo de secretos local por la plantilla: conserva las claves de cifrado existentes, porque los audios guardados dependen de la clave con la que se cifraron. Las credenciales reales nunca deben copiarse a la plantilla ni subirse al repositorio.

## Gestión de usuarios y llamadas (API 1.0.2)

- La gestión CRUD de usuarios (`/api/users`) está restringida al rol `administrator`. La API permite consultar por ID, crear, editar rol/institución/datos de perfil y eliminar. No se puede eliminar la propia cuenta ni el último administrador activo. Los cambios de perfil invalidan los JWT emitidos anteriormente para ese usuario.
- Las llamadas pueden consultarse por ID (`GET /api/llamadas/{id}`) respetando la institución del usuario y las instituciones sugeridas.
- Supervisores y administradores pueden corregir el resumen operativo y la dirección de un caso abierto (`PUT /api/llamadas/{id}`). Si la dirección cambia, se intenta actualizar su geolocalización; si el proveedor no devuelve coordenadas, se conserva la dirección editada y el mapa puede quedar sin coordenadas.
- Solo administradores pueden retirar una llamada de la operación. `DELETE /api/llamadas/{id}` archiva lógicamente un caso pendiente que aún no está asignado. No elimina físicamente la fila y los casos asignados o cerrados se preservan como historial.
- Las creaciones responden `201 Created` con `Location`; el archivo lógico y la eliminación de usuarios responden `204 No Content`. Los errores de validación son `400`, los recursos inexistentes/no visibles `404`, los accesos no autorizados `401`/`403` y los conflictos de estado o unicidad `409`.
- El campo de archivo lógico se agrega a la base H2 existente mediante la actualización automática de esquema de Hibernate; los registros históricos existentes se consideran no archivados.

Las rutas, formatos JSON, roles y ejemplos de solicitudes están documentados en la sección **API REST y contratos** de [README.md](README.md). Para probar cambios de esquema, respalda la carpeta `data/` con CODES detenido antes de instalar una versión nueva.

## Mapa y servicios cartograficos

Leaflet y sus recursos se sirven desde el JAR. El mapa base predeterminado es **Esri World Imagery** (satélite); las rutas usan OSRM Demo y la geocodificación usa Geoapify.

- La atribución de Esri se muestra en el mapa: `© Esri — Source: Esri, Vantor, Earthstar Geographics, and the GIS User Community`.
- Las direcciones de incidentes se envían a Geoapify; al calcular una ruta, las coordenadas del operador y del incidente se envían a OSRM. El audio de las llamadas se envía a Groq Whisper para transcribirlo. La interfaz indica este intercambio.
- No uses esos servicios con datos reales sensibles sin aprobación y revisión de privacidad, políticas y normativa aplicable. Para operación crítica, contrata proveedores con SLA y acuerdos de tratamiento adecuados.
- Esri World Imagery se sirve desde [ArcGIS Online](https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer); consulta los [términos de Esri](https://www.esri.com/en-us/legal/terms/full-master-agreement).
- Los endpoints se pueden cambiar con `CODES_MAP_TILES_URL`, `CODES_ROUTING_URL`, `GROQ_BASE_URL` y `GEOAPIFY_BASE_URL`. Las URLs de teselas deben ser plantillas compatibles con Leaflet; los endpoints personalizados deben respetar el formato esperado por Leaflet, Groq o Geoapify.

## Respaldo y restauracion

CODES.bat permite crear un respaldo local. Tambien se puede ejecutar:

```powershell
.\tools\backup_codes.ps1 -IncluirLogs
.\tools\restore_codes.ps1 -Respaldo .\backups\codes_YYYYMMDD_HHMMSS
```

CODES debe estar detenido para respaldar o restaurar la base H2. El respaldo incluye base de datos, audios, cache, logs y secretos. Es informacion sensible y debe protegerse.
La carpeta `backups/` está excluida de Git: conserva los respaldos fuera del repositorio y limita su acceso.

La perdida de `CODES_ENCRYPT_KEY` impide recuperar audios cifrados.

## Seguridad y evidencia

La aplicacion implementa JWT, BCrypt, roles, aislamiento institucional, invalidacion de sesiones, CORS restringido, limites de intentos, auditoria, cifrado AES-256-GCM y ALTCHA autoalojado con Argon2id. El widget y su worker se sirven localmente, sin proveedor de CAPTCHA. El uso de desafíos queda persistido en H2 para bloquear repeticiones aun después de reiniciar. Desde equipos de la LAN se requiere HTTPS para ALTCHA, micrófono y geolocalización; HTTP solo es compatible con `localhost`. Argon2id eleva el costo del abuso automatizado, pero no basta para frenar bots sofisticados en Internet ni sustituye los controles anti-abuso en proxy/WAF.

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
login -> llamada -> Groq Whisper o transcripcion manual -> prioridad -> direccion
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

El autochequeo revisa base de datos, disco, espacio libre, claves de Groq y Geoapify y cache de geocodificacion. Los servicios externos pueden quedar en modo degradado sin impedir la operacion manual.

## Limites actuales

CODES distribuye alertas simultaneamente dentro de sus colas locales segun las instituciones sugeridas. Todavia no envia alertas a sistemas externos de Carabineros, SAMU o Bomberos.

## Siguiente etapa: web real y dispositivos

La version actual no debe publicarse directamente en Internet. Para habilitar acceso desde telefonos o computadores de otras redes, seguir este orden:

1. Definir responsable operativo, jurisdiccion, retencion de audios/transcripciones, consentimiento, control de acceso y procedimiento ante incidentes. No usar datos reales hasta acordar estas reglas.
2. Preparar un servidor Linux o Windows con dominio, DNS, firewall y almacenamiento cifrado. Mantener la aplicacion y H2 en almacenamiento persistente; para varias instancias o alta disponibilidad, migrar a PostgreSQL y agregar migraciones versionadas.
3. Colocar Caddy o Nginx delante de Spring Boot, emitir HTTPS y reenviar solo el trafico web necesario. Mantener el puerto 8000 fuera de Internet; permitir acceso a Spring Boot solo desde el proxy.
4. Probar login, captura de microfono y geolocalizacion desde los dispositivos reales. Los navegadores requieren HTTPS (salvo `localhost`) para microfono y geolocalizacion.
5. Para operacion critica, contratar proveedores con SLA para transcripcion, geocodificacion, teselas y rutas; configurar sus endpoints con variables de entorno y guardar las claves en un gestor de secretos.
6. Configurar secretos fuera del repositorio, CORS con el origen HTTPS exacto, proteccion anti-abuso en el proxy/WAF, SMTP propio si se requiere recuperacion de clave y monitorizacion/alertas.
7. Automatizar respaldos cifrados de BD, audios, claves y datos cartograficos, y ensayar restauracion. Definir retencion y borrado antes de guardar llamadas reales.
8. Hacer pruebas de seguridad, concurrencia, navegadores y perdida de red; ejecutar un piloto cerrado con operadores antes de integrar o comunicar alertas a servicios oficiales.

La solucion actual no integra despachos oficiales y el clasificador automatico no reemplaza el criterio de un operador. HTTPS, PostgreSQL, migraciones, backups programados, monitoreo e integraciones oficiales siguen siendo trabajo de produccion.
