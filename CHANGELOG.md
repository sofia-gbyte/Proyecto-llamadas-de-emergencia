# Historial de cambios

Este archivo resume los cambios funcionales y de operación relevantes por versión de CODES. Las versiones de las bibliotecas se mantienen documentadas por separado en [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) y en `pom.xml`.

## [1.0.2] - 2026-11-03

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
- Verificación de entrega: 52 pruebas Maven aprobadas; smoke test del app-image con HTML, Leaflet, `script.js` y heartbeat local.

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
