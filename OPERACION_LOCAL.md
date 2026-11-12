# Operacion local de CODES

Guía de instalación, respaldo, seguridad y recuperación local. Esta guía corresponde a CODES `1.1.1` (versión del artefacto definida en `pom.xml`). Esta versión está destinada a pruebas locales de baja capacidad con datos sintéticos; no está preparada para exposición a una LAN/Internet ni para despacho de emergencias reales.

Consulta [README.md](README.md) para la arquitectura, los contratos de la API y las decisiones técnicas, y [CHANGELOG.md](CHANGELOG.md) para los cambios de esta versión.

## Inicio en Windows

Para usar o actualizar el app-image, abre `dist\CODES\CODES.exe`. Mantén la carpeta completa `CODES` junto con su directorio `runtime`; no copies ni distribuyas solo el EXE. El menú permite iniciar/detener el sistema, configurar integraciones, ejecutar diagnósticos locales, crear respaldos y limpiar archivos regenerables. Ya no incluye una acción que pueda borrar todos los casos y audios desde el menú. El lanzador por lotes fue retirado; los scripts PowerShell de desarrollo y mantenimiento no se requieren ni se incluyen en el app-image de usuario. Para actualizar, cierra CODES y reemplaza el app-image en esa misma ruta con `.\tools\package_codes_windows.ps1 -ReplaceExisting`; los datos de usuario están en otra carpeta y no se copian ni borran durante el empaquetado.

Al elegir **Iniciar sistema completo**, el EXE inicia Spring Boot y abre `http://127.0.0.1:8000` en el navegador cuando está listo. El servidor escucha únicamente en loopback: las direcciones no locales se rechazan y el proceso fuerza el enlace a `127.0.0.1`. No abras puertos del firewall ni configures un proxy para permitir acceso remoto. El app-image ya incluye su runtime Java, por lo que su ejecución no requiere instalar Java ni Maven. ALTCHA protege el login y el registro sin consultar servicios externos.

### Persistencia, permisos y recuperación

La carpeta persistente predeterminada es siempre `%LOCALAPPDATA%\CODES\data` en cualquier versión (si no existe `LOCALAPPDATA`, se usa `%USERPROFILE%\.codes\data`). CODES reutiliza la carpeta y los archivos existentes; solo crea directorios/archivos faltantes en el primer arranque. Contiene H2, audios, caché, auditoría y secretos, y queda fuera del app-image para conservarlos al reemplazar `dist\CODES`. Hibernate aplica actualizaciones de esquema al iniciar; antes de cada actualización crea un respaldo y, una vez actualizado el esquema, no vuelvas a una versión anterior salvo que restaures el respaldo correspondiente. `CODES_DATA_DIR` puede seleccionar explícitamente otra ubicación persistente, pero el inicio se detiene si no se puede escribir o si la carpeta está dentro de la instalación. No elimines ni reemplaces la carpeta para reparar un inicio fallido: primero crea un respaldo, cierra CODES y comprueba el espacio disponible y los permisos. En sistemas de archivos que lo permiten, CODES restringe archivos y carpetas al usuario actual; en los demás se aplican los permisos predeterminados del sistema operativo.

La base H2 y los archivos de secretos no están cifrados en reposo; solo los audios usan AES-256-GCM. Protege el equipo, el directorio de datos y los respaldos con controles del sistema operativo. Las ACL no protegen contra administradores, malware ni acceso físico y no sustituyen el cifrado. Hasta implementar y revisar el cifrado en reposo y la gestión de claves, utiliza únicamente datos sintéticos.

Si el puerto local 8000 está ocupado, cierra la instancia/aplicación que lo utiliza e inténtalo otra vez. Si el directorio de datos no está disponible, restaura el acceso a la carpeta o corrige `CODES_DATA_DIR`; CODES no crea datos dentro del app-image como alternativa. Los mensajes de error de la interfaz ocultan detalles internos y secretos, y pueden incluir una referencia local. Los registros de auditoría se guardan en `logs\auditoria.log` dentro del directorio de datos configurado.

### Claves de los proveedores

Por privacidad, **las integraciones externas están desactivadas por defecto**. Sin activarlas, CODES no envía audios, direcciones, coordenadas ni correos a proveedores. Para configurar el mapa y las APIs, abre el EXE y elige **2. Configurar integraciones y API**. La ventana permite configurar Groq, Geoapify Geocoding/Places/Routing/Maps, SMTP, mapa y rutas, sin editar archivos a mano.

Los campos de claves se muestran enmascarados y nunca se vuelven a mostrar; déjalos vacíos para conservar claves ya guardadas. La activación es global: al habilitarla, Groq puede recibir audio; Geoapify, direcciones/nombres y, según el proveedor elegido, coordenadas; y SMTP, correos. Esri World Imagery y OSRM Demo son los endpoints predeterminados y no requieren claves propias. Aprueba cada flujo y los términos del proveedor antes de habilitarla. Después de guardar, cierra y vuelve a abrir el EXE para cargar la configuración. Routing y Maps se proxyean desde el servidor local para no exponer sus claves al navegador.

Los ajustes se guardan en `%LOCALAPPDATA%\CODES\data\.codes-secrets.properties` (o en el directorio de `CODES_DATA_DIR`) mediante reemplazo atómico y con permisos restringidos al usuario actual cuando Windows lo permite. El archivo y las claves permanecen en texto plano; restringe el acceso al equipo y cifra los respaldos. Las variables de entorno tienen prioridad sobre la configuración guardada. El diagnóstico local muestra si Groq, Geoapify Geocoding, Places, Routing y Maps están configurados, sin revelar las claves ni contactar proveedores.

Para CODES 1.1.1 se integran Places (coincidencia estricta de hospitales/centros de salud cuando la búsqueda de dirección falla), Routing (alternativa al OSRM público) y Map Tiles (estilos OSM Carto, Bright y Toner). Places entrega nombre, dirección, categoría y coordenadas; Place Details existe, pero no se invoca hasta que CODES tenga una vista que use detalles adicionales (por ejemplo, población o geometría). Reverse Geocoding, Autocomplete, Route Matrix, Route Planner, Isoline, Map Matching, IP Geolocation y Batch no se activan porque no existe todavía un flujo local que los use de forma segura y útil. Las claves Geoapify deben tener habilitadas las APIs correspondientes en el proyecto del proveedor.

La plantilla de nombres de variables está en `codes-secrets.example.ps1`, en la raíz del proyecto fuente. No contiene credenciales válidas: reemplaza sus marcadores localmente antes de usarla. En una instalación nueva, CODES crea sus archivos de secretos bajo `%LOCALAPPDATA%\CODES\data`.

No reemplaces automáticamente el archivo de secretos local por la plantilla: conserva las claves de cifrado existentes, porque los audios guardados dependen de la clave con la que se cifraron. Las credenciales reales nunca deben copiarse a la plantilla ni subirse al repositorio.

## Gestión de usuarios y llamadas (API 1.1.1)

- La gestión CRUD de usuarios (`/api/users`) está restringida al rol `administrator`. La API permite consultar por ID, crear, editar rol/institución/datos de perfil y eliminar. No se puede eliminar la propia cuenta ni el último administrador activo. Los cambios de perfil invalidan los JWT emitidos anteriormente para ese usuario.
- Las llamadas pueden consultarse por ID (`GET /api/llamadas/{id}`) respetando la institución del usuario y las instituciones sugeridas.
- Supervisores y administradores pueden corregir el resumen operativo y la dirección de un caso abierto (`PUT /api/llamadas/{id}`). Si la dirección cambia, se intenta actualizar su geolocalización; si el proveedor no devuelve coordenadas, se conserva la dirección editada y el mapa puede quedar sin coordenadas.
- Solo administradores pueden retirar una llamada de la operación. `DELETE /api/llamadas/{id}` archiva lógicamente un caso pendiente que aún no está asignado. No elimina físicamente la fila y los casos asignados o cerrados se preservan como historial.
- Solo administradores pueden archivar todos los casos activos mediante `POST /api/llamadas/archive-all`. Incluye pendientes, en curso y cerrados, y responde `200 OK` con `archivedCount`. La acción oculta los casos de las colas sin borrar filas ni audios; no hay restauración desde la interfaz, por lo que se recomienda verificar un respaldo antes de confirmar.
- CODES elimina automáticamente los casos con más de dos meses desde su fecha de creación, incluidos los que sigan pendientes o en curso y los casos archivados. La tarea elimina la fila de H2 y el audio cifrado asociado, siempre que el archivo esté directamente dentro del directorio configurado de audios. Se ejecuta al iniciar CODES y diariamente a las 04:00 mientras el servicio está activo; si el programa permanece cerrado, se ejecuta en el siguiente inicio.
- La retención automática no depura copias de seguridad ni garantiza sobrescritura forense de bloques del archivo H2 o de unidades SSD. Las copias externas/locales deben tener una política de retención independiente; revisa respaldos antes de tratarlos como eliminados definitivamente.
- Las creaciones responden `201 Created` con `Location`; el archivo lógico y la eliminación de usuarios responden `204 No Content`. Los errores de validación son `400`, los recursos inexistentes/no visibles `404`, los accesos no autorizados `401`/`403` y los conflictos de estado o unicidad `409`.
- El campo de archivo lógico se agrega a la base H2 existente mediante la actualización automática de esquema de Hibernate; los registros históricos existentes se consideran no archivados.

Las rutas, formatos JSON, roles y ejemplos de solicitudes están documentados en la sección **API REST y contratos** de [README.md](README.md). Para probar cambios de esquema, respalda la carpeta `data/` con CODES detenido antes de instalar una versión nueva.

## Mapa y servicios cartograficos

Leaflet y sus recursos se sirven desde el JAR. Las integraciones de mapa, rutas, geocodificación, transcripción y correo están desactivadas por defecto. Al habilitarlas explícitamente, el mapa base predeterminado es **Esri World Imagery** (satélite), las rutas usan OSRM Demo y la geocodificación usa Geoapify. Geoapify Maps y Routing son opciones explícitas configurables desde el EXE; las claves se transmiten al proveedor solo desde el servidor local. En la pestaña **Mapa y geocodificación** puedes ajustar el radio (mayor que 0 y hasta 100 km); por defecto es 8 km. El centro de cobertura se obtiene del GPS del operador solo al pulsar **Ubicación actual**; si no se activa, las búsquedas que no incluyen ubicación de operador usan el centro de respaldo configurable del menú. En llamadas en vivo, Geoapify debe identificar la dirección con suficiente certeza fuera del radio para rechazar el alta y mostrar el motivo; si la ubicación no se puede resolver o el proveedor no está disponible, se permite guardar el caso sin coordenadas para no perder una emergencia. Las variables de entorno `CODES_GEOCODER_CENTER_LAT`, `CODES_GEOCODER_CENTER_LNG` y `CODES_GEOCODER_RADIUS_KM`, si están definidas, tienen prioridad sobre los valores guardados en el menú.

- La atribución de Esri se muestra en el mapa: `© Esri — Source: Esri, Vantor, Earthstar Geographics, and the GIS User Community`.
- Las direcciones/nombres de incidentes se envían a Geoapify; al calcular una ruta, las coordenadas del operador y del incidente se envían a OSRM o Geoapify, según la opción seleccionada. El audio de las llamadas se envía a Groq Whisper para transcribirlo. La interfaz indica este intercambio.
- No uses esos servicios con datos reales sensibles sin aprobación y revisión de privacidad, políticas y normativa aplicable. Para operación crítica, contrata proveedores con SLA y acuerdos de tratamiento adecuados.
- Esri World Imagery se sirve desde [ArcGIS Online](https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer); consulta los [términos de Esri](https://www.esri.com/en-us/legal/terms/full-master-agreement).
- Los endpoints se pueden cambiar con `CODES_MAP_TILES_URL`, `CODES_ROUTING_URL`, `GROQ_BASE_URL` y `GEOAPIFY_BASE_URL`. Los proveedores y credenciales se guardan desde el menú del EXE; las URL de teselas personalizadas deben ser HTTPS y compatibles con Leaflet.
- Las URL personalizadas de teselas y rutas se entregan al navegador. No incluyas en ellas claves privadas; usa claves públicas con restricciones del proveedor o selecciona los proxies locales de Geoapify Maps/Routing.

## Respaldo y restauracion

Usa **Crear respaldo local** desde el menú del EXE y sigue la confirmación de seguridad. El respaldo incluye información confidencial y se guarda fuera del app-image. Para tareas de mantenimiento del proyecto fuente también se puede ejecutar:

```powershell
.\tools\backup_codes.ps1 -IncluirLogs
.\tools\restore_codes.ps1 -Respaldo .\backups\codes_YYYYMMDD_HHMMSS
```

CODES debe estar detenido para respaldar o restaurar la base H2. El respaldo incluye base de datos, audios, cache, logs y secretos. Es informacion sensible y debe protegerse.
La carpeta `backups/` está excluida de Git: conserva los respaldos fuera del repositorio y limita su acceso.

La perdida de `CODES_ENCRYPT_KEY` impide recuperar audios cifrados.

## Seguridad y evidencia

La aplicación implementa JWT, BCrypt, roles, aislamiento institucional, invalidación de sesiones, CORS restringido, límites de intentos, auditoría, cifrado AES-256-GCM de audios y ALTCHA autoalojado con Argon2id. El widget y su worker se sirven localmente, sin proveedor de CAPTCHA. El uso de desafíos queda persistido en H2 para bloquear repeticiones aun después de reiniciar. **H2 y los secretos permanecen en texto plano en disco.** Solo se admite uso en el mismo equipo; el servidor escucha en loopback y rechaza direcciones no locales. No se admite acceso desde otros equipos. Argon2id eleva el costo del abuso automatizado, pero no basta para frenar bots sofisticados ni sustituye otros controles.

El EXE no está firmado digitalmente. El app-image genera un manifiesto y hashes SHA-256 para comprobar integridad, pero solo son confiables si se obtienen por un canal autenticado; no verifican quién publicó el paquete. No expongas el servidor ni uses datos reales sensibles sin resolver las limitaciones de cifrado, distribución y servicios externos descritas aquí.

Antes de una entrega o instalacion operativa se debe comprobar:

- Peticion sin JWT: responde `401` o `403`.
- Usuario desactivado: no puede usar su JWT anterior.
- Operador: solo ve casos propios o instituciones sugeridas.
- Operador: no cierra casos ajenos o no asignados.
- Supervisor: puede cerrar casos de su institucion.
- Administrador: puede supervisar todas las instituciones.
- Logout, cambio de clave y cierre forzado invalidan JWT anteriores.
- Audio con tipo o extension no permitidos: se rechaza.
- Eventos operativos: quedan en `<directorio de datos>\logs\auditoria.log`.

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

## Acceso desde otros equipos

No habilites acceso LAN/Internet con esta versión: el servicio fuerza loopback y rechaza direcciones públicas o privadas no-loopback. La exposición multi-equipo requiere un cambio de arquitectura y una revisión de seguridad independiente; no se resuelve ajustando `CODES_SERVER_ADDRESS`, CORS o el firewall. CODES no integra despachos oficiales y el clasificador automático no reemplaza el criterio de un operador.
