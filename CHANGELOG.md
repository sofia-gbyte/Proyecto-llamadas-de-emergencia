# Historial de cambios

Este archivo resume los cambios funcionales y de operación relevantes por versión de CODES. Las versiones de las bibliotecas se mantienen documentadas por separado en [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) y en `pom.xml`.

## [1.1.1] - 2026-11-11

### Geocodificación y cobertura

- El radio de cobertura predeterminado queda en 8 km. Al pulsar **Ubicación actual**, el dispositivo del operador pasa a ser el centro visible del área y de la búsqueda Geoapify para llamadas en vivo.
- Si Geoapify identifica con suficiente certeza que una ubicación está fuera del radio, no se crea el caso y la interfaz muestra el motivo. Si la ubicación no se puede resolver o el proveedor no está disponible, se conserva el registro sin coordenadas para no perder una emergencia.
- La geocodificación exige coincidencia suficiente con las palabras específicas de la ubicación; ubicaciones genéricas o candidatas con nombre distinto no reciben coordenadas confirmadas. Geoapify Places sirve como respaldo para hospitales y centros de salud nombrados.
- El mapa dibuja el círculo de cobertura y encuadra el área cuando no hay un incidente seleccionado.
- Geoapify Routing está disponible como alternativa configurable a OSRM. Geoapify Maps ofrece estilos OSM opcionales; Routing y las teselas se proxyean desde el servidor local para que sus claves no se expongan al navegador.
- El EXE permite guardar claves separadas de Geoapify Places, Routing y Maps en la configuración local enmascarada. Place Details, Reverse Geocoding, Autocomplete, Matrix, Planner, Isoline, Map Matching, IP Geolocation y Batch se posponen porque no corresponden a flujos actuales de CODES.

### Gestión y clasificación de llamadas

- Se eliminan diariamente los casos con más de dos meses desde su creación, sin importar si están pendientes, en curso, cerrados o archivados. La limpieza elimina el audio cifrado asociado, registra la ejecución en auditoría y corre al iniciar CODES y cada día a las 04:00; las copias de respaldo no se eliminan.
- Los administradores pueden archivar lógicamente todos los casos activos tras una confirmación explícita; la acción se audita y no elimina los registros ni los audios.
- URGENTE se distingue de ROJA con morado intermitente, respetando la preferencia de movimiento reducido.
- Se integra el diccionario local de emergencias para Carabineros, SAMU y Bomberos, se amplían señales médicas y se mantiene el control de negaciones.
- Las actualizaciones simultáneas de cola comparten una consulta, conservan los datos anteriores si falla una solicitud y descartan respuestas obsoletas al cambiar o cerrar sesión.
- Los errores al guardar llamadas en vivo permanecen visibles, incluyen HTTP y referencia del servidor cuando están disponibles, y conservan el audio para reintentar.
- La base persistente no se migra ni se borra en estos cambios.

### Validación y distribución

- La versión del artefacto Maven, la caché del navegador y el empaquetador Windows se alinean a `1.1.1`.
- `mvn clean verify` pasó con 112 pruebas; el app-image oficial Windows 1.1.1 se regeneró y sus 207 archivos se verificaron contra el manifiesto y sus SHA-256.

## [1.1.0] - 2026-11-03

### Integraciones y llamadas en vivo

- Los errores de Groq ahora indican cómo resolver claves inválidas, cuotas agotadas, formato/tamaño de audio, fallos temporales y problemas de conexión, sin exponer claves ni respuestas internas del proveedor.
- El límite de audio de llamadas en vivo se alinea al máximo de 25 MB de Groq; los errores de transcripción se entregan como respuestas `502` seguras y comprensibles para la interfaz.
- Se invalidó la caché del JavaScript del navegador para que las instalaciones actualizadas carguen la corrección del guardado en vivo.
- Se añadieron pruebas simuladas para claves rechazadas, límites de cuota y fallos temporales. Las pruebas no contactan a Groq ni envían audio real.

### Menú y distribución local

- Se alineó la tarjeta de **ALMACENAMIENTO LOCAL** con las tarjetas de estado y actividad del menú del EXE.
- Se retiró del menú la acción de borrado de casos y audios, que podía afectar todos los registros en vez de solo los de prueba.
- Se actualizó el empaquetado Windows, el artefacto Maven y la documentación activa a la versión `1.1.0`; el destino estable sigue siendo `dist\CODES` y los datos permanecen en la carpeta local persistente.
- `mvn clean verify` pasó con 89 pruebas; el app-image incluye 207 archivos verificados, runtime Java y un único `CODES.exe`. SHA-256 del EXE: `A1B89D1A047DC395DD6C44C92FE256FD23C0AEEDE2BBAF988CDDE9C32638E789`; SHA-256 de `package-manifest.json`: `2E28C77E5FCE3B9D12B39E4D95B76A83609A385ABA0A82ACFA11A16C8FF4D4F7`.
- El alcance continúa limitado a pruebas locales de baja capacidad con datos sintéticos; la versión no está certificada para despacho real ni para almacenar datos personales sensibles.

## [1.0.2] - 2026-11-03

### Preparación de app-image local

- Se migró a Spring Boot 4.0.9 y se conservaron los contratos existentes; la suite Maven finalizó con 84 pruebas aprobadas.
- Se desactivaron por defecto las integraciones externas para evitar el envío no consentido de audios, direcciones, coordenadas o correos. La activación requiere `CODES_EXTERNAL_SERVICES_ENABLED=true`; la interfaz no cambia automáticamente a proveedores públicos si falla el proveedor configurado.
- El menú de Windows ahora incluye **Configurar integraciones y API** para introducir claves de Groq, Geoapify y SMTP y personalizar mapa/rutas sin editar archivos manualmente. Las claves se guardan en el perfil local con permisos restringidos; hay que reiniciar el EXE para aplicar cambios.
- Se rediseñó el menú Swing con la paleta azul petróleo/turquesa de la consola web, el logo incluido en el EXE, estado de servicio y actividad reciente. Se agruparon respaldo, limpieza y casos de prueba en **Datos y respaldos**, con etiquetas más breves y acceso a la carpeta persistente.
- El logo borroso se sustituyó por el original azul y blanco compartido para esta entrega, tanto en el menú como en el icono multirresolución de Windows.
- En las llamadas en vivo, el envío confirmado ya no se confunde con un error al refrescar la interfaz; se evita conservar el audio como pendiente de reintento si el servidor ya respondió que se guardó.
- La ubicación estable del app-image es `dist\CODES`; las versiones actualizadas reemplazan ese paquete y reutilizan `%LOCALAPPDATA%\CODES\data` (o `%USERPROFILE%\.codes\data` como alternativa), sin cambiar de carpeta de datos.
- El paquete `dist\CODES` se generó desde el JAR probado, con runtime Java 21.0.12.1 y el menú gráfico de configuración de proveedores; se debe mantener la carpeta completa junto al runtime.
- SHA-256 de `CODES.exe`: `9A84E3CB53D9A68CDD5F050C9A963821FD60E4CE83DA32F240C20FDDD1197D9F`; SHA-256 de `package-manifest.json`: `CA2BF39700D42FEBFC81A7612B49936111AAFEF8053B82B0420CA8471E5C6BB6`.
- La suite final `mvn clean verify` pasó con 84 pruebas; se verificaron los hashes del paquete y la ausencia de scripts y datos locales. Las pruebas no contactan servicios externos reales.
- Se comprobó que un intento de enlazar el servidor a `0.0.0.0` se rechaza y que el paquete no incluye scripts, secretos, bases de datos ni audios locales.
- La solicitud de recuperación de contraseña informa al usuario cuando el correo está desactivado; no intenta contactar al endpoint ni a un servidor SMTP.
- El escaneo de dependencias no reportó CVE conocidos en las coordenadas enviadas. Esto no equivale a una auditoría de código ni a una certificación de seguridad.
- La documentación aclara que H2 y los secretos no están cifrados en reposo, que el EXE no está firmado y que los servicios externos pueden recibir datos; el paquete es para pruebas locales con datos sintéticos, no para despacho real.

### Identidad visual de Windows

- Se actualizó el icono de `CODES.exe` con la imagen de marca de la aplicación.

### Refuerzo de ALTCHA

- Se migró la verificación a ALTCHA v2 con Argon2id (1 iteración, 8 MiB, paralelismo 1); el widget, worker y traducción española se sirven desde el propio proyecto.
- Los desafíos se vinculan a login o registro, vencen a los cinco minutos y se firman con HMAC.
- El uso de desafíos se guarda en H2 con una restricción única, evitando reuso incluso tras reinicios o solicitudes concurrentes.
- Se limitó a dos el número de verificaciones Argon2id concurrentes para acotar el consumo de memoria del servidor.
- La autenticación ALTCHA desde equipos de la LAN ahora requiere HTTPS; HTTP queda disponible para `localhost`.
- El perfil equilibrado tardó aproximadamente 13 segundos en una prueba del widget en el navegador de desarrollo; el tiempo varía según el dispositivo.

### Alcance de seguridad

- Argon2id hace más costosa la automatización y reduce la ventaja del hardware acelerado; el estado persistido elimina la ventana de repetición por reinicio.
- ALTCHA no demuestra por sí solo que el cliente sea humano ni es protección suficiente para exposición directa a Internet. Mantener rate limits y controles del proxy/WAF.

### Seguimiento y clasificación de casos

- Se pueden agregar antecedentes a una llamada abierta sin crear un caso duplicado; cada aporte conserva autoría, fecha e identificador de idempotencia.
- La información complementaria actualiza prioridad, motivos y recomendaciones internas. No cambia automáticamente la ubicación ni activa despachos externos.
- Se reconocen variantes plurales de personas atrapadas en la clasificación.

### Privacidad y distribución local

- Los errores de transcripción ya no exponen el cuerpo de respuesta del proveedor y los logs no registran transcripciones ni nombres de archivos de audio.
- Se agregó el empaquetado Windows autocontenido con `jpackage`; la app-image incluye su runtime Java y utiliza `%LOCALAPPDATA%\CODES\data`.
- Leaflet se sirve desde la ruta real incluida en el WebJar, evitando depender de una CDN para cargar la biblioteca.
- En una validación anterior de esta versión: 52 pruebas Maven aprobadas y smoke test de recursos web del app-image; la verificación final de esta entrega se describe en **Preparación de app-image local**.

## [1.0.1] - 2026-11-02

### Añadido

- Protección ALTCHA local en inicio de sesión y registro, con desafíos firmados y de expiración corta.
- Límite de solicitudes al endpoint de desafíos y rechazo de soluciones reutilizadas durante la ejecución del servidor.
- Clave HMAC de ALTCHA generada y persistida automáticamente por el lanzador de Windows.
- Validación de tamaño máximo del token CAPTCHA en los DTO de autenticación.

### Auditoría y limitaciones conocidas en 1.0.1

- Suite completa: 39 pruebas aprobadas; prueba de generación/verificación del desafío y rechazo de repetición.
- La integración usa ALTCHA Java v1 con SHA-256 y un espacio de búsqueda máximo de 100.000; no usa Argon2id/Scrypt ni el widget oficial actual. La prueba de trabajo es resoluble con hardware acelerado y no demuestra por sí sola que el cliente sea humano.
- El registro de desafíos usados es local a la instancia y en memoria: se reinicia al reiniciar CODES y no se comparte entre instancias.
- El worker SHA-256 propio no requiere HTTPS para resolver desafíos desde la LAN. HTTPS sigue siendo necesario en redes no confiables para proteger credenciales y tokens, y para APIs del navegador como micrófono y geolocalización. No exponer ALTCHA por sí solo a Internet; mantener límites adicionales y controles anti-abuso en proxy/WAF.
- La revisión no constituye certificación de seguridad ni recomienda desplegar el sistema en operación crítica.

## [1.0.0] - 2026-11-01

Primera versión 1.0 del proyecto, enfocada en consolidar la API y documentar su alcance operativo.

### Añadido

- CRUD REST de usuarios: listado, consulta por ID, creación, actualización de perfil/rol/institución y eliminación administrativa.
- Consulta individual de llamadas y actualización del resumen operativo y la dirección de casos abiertos.
- Respuestas `201 Created` con cabecera `Location` al crear usuarios y llamadas.
- Archivo lógico de llamadas pendientes sin asignar, con conservación de la fila histórica y exclusión de colas y métricas operativas.
- Plantilla `codes-secrets.example.ps1` en la raíz, sin credenciales reales.
- Documentación de decisiones de arquitectura, contratos REST, roles, formatos de solicitud, códigos HTTP y notas de instalación/compatibilidad.
- Pruebas para reglas de archivo lógico, aislamiento de consultas operativas y respuestas de error HTTP.

### Seguridad y reglas operativas

- La API de usuarios se mantiene restringida a administradores; no permite eliminar la propia cuenta ni al último administrador activo.
- Los cambios de datos/permisos de un usuario invalidan sus JWT existentes.
- Solo supervisores y administradores pueden corregir resumen y dirección de llamadas abiertas.
- La eliminación lógica de llamadas solo está permitida para administradores y nunca para casos asignados o cerrados.
- Los registros de llamadas archivadas permanecen en la base de datos para preservar la trazabilidad.

### Compatibilidad y alcance

- La columna `deleted` de llamadas se incorpora mediante `spring.jpa.hibernate.ddl-auto=update`; las filas preexistentes se consideran activas/no archivadas.
- Antes de actualizar, respaldar `data/` con la aplicación detenida. No sustituir las claves locales, en particular `CODES_ENCRYPT_KEY`, pues se necesitan para descifrar audios existentes.
- CODES `1.0.0` está dirigido a demostración y uso local/red interna controlada. No debe exponerse directamente a Internet ni utilizarse para despacho real sin revisiones de seguridad, privacidad, legales, disponibilidad y proveedores.

### Verificación

- Suite de pruebas: 35 pruebas aprobadas en el entorno de desarrollo para esta entrega.
- La comprobación no constituye certificación de seguridad ni validación de despliegue en un equipo limpio.
