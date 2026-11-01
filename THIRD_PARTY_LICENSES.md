# Licencias, datos y servicios externos

Revision inicial del proyecto al 2026-09-29. Este documento identifica componentes visibles en el codigo y dependencias directas; no es asesoramiento legal ni sustituye la revision del artefacto final.

Versión de CODES documentada: `1.0.0` (definida en `pom.xml`). Este número identifica el artefacto de la aplicación; no representa la versión de Spring Boot ni de las dependencias.

## Codigo y dependencias directas

| Componente | Version/uso | Licencia o terminos | Observaciones |
| --- | --- | --- | --- |
| Codigo CODES de este repositorio | Aplicacion | Sin licencia propia declarada | No asumir permiso de redistribucion o uso comercial del codigo del proyecto sin autorizacion de sus titulares. |
| Spring Boot y proyectos Spring | 3.3.4 | Apache License 2.0 | Revisar avisos de copyright en dependencias distribuidas. |
| H2 Database | Version resuelta por Spring Boot 3.3.4 | MPL 2.0 o EPL 1.0 | Confirmar la version exacta en el arbol de dependencias del release. |
| JJWT | 0.12.6 | Apache License 2.0 | Incluye modulos API, implementacion y Jackson. |
| Leaflet | 1.9.4, empaquetado como WebJar | BSD 2-Clause | El navegador obtiene los archivos desde el propio JAR de CODES; no necesita un CDN en ejecucion. |

Enlaces de referencia: [Spring Boot](https://github.com/spring-projects/spring-boot/blob/v3.3.4/LICENSE.txt), [H2](https://www.h2database.com/html/license.html), [JJWT](https://github.com/jwtk/jjwt/blob/0.12.6/LICENSE), [Leaflet](https://github.com/Leaflet/Leaflet/blob/v1.9.4/LICENSE).

El JDK de ejecucion depende de la distribucion elegida (por ejemplo, Temurin u Oracle). Elegirla y revisar sus terminos en el servidor de produccion.

## Datos y servicios de mapas

| Elemento | Estado actual | Licencia/condiciones |
| --- | --- | --- |
| Imágenes satelitales | Esri World Imagery desde ArcGIS Online | Mostrar la atribución indicada por el servicio (`© Esri — Source: Esri, Vantor, Earthstar Geographics, and the GIS User Community`) y respetar los [términos de Esri](https://www.esri.com/en-us/legal/terms/full-master-agreement). El servicio público no ofrece un SLA contratado por este proyecto. |
| Transcripción de audio | Groq Whisper API | Servicio externo sujeto a los términos, límites y disponibilidad de Groq. El audio se transmite al proveedor cuando se crea una llamada. |
| Geocodificacion | Geoapify API | Servicio externo sujeto a sus términos, cuotas y disponibilidad. La dirección de la llamada se transmite al proveedor al geocodificar. |
| Rutas | OSRM Demo, configurable mediante `CODES_ROUTING_URL` | Servicio público de mejor esfuerzo, sin SLA; recibe las coordenadas de origen y destino cuando se solicita una ruta. |
| Geolocalizacion del operador | API de geolocalizacion del navegador, solo bajo accion del usuario | La posicion depende del dispositivo/OS y permisos del navegador; no se envia como posicion del incidente. |

Las imágenes del mapa usan Esri World Imagery y muestran su crédito. Consultar los [términos de Esri](https://www.esri.com/en-us/legal/terms/full-master-agreement).

Los endpoints publicos son de mejor esfuerzo, no ofrecen SLA y no deben recibir datos personales o confidenciales. Usarlos solo con datos de prueba; las direcciones y coordenadas de incidentes reales pueden ser sensibles. Para produccion, contratar un proveedor apropiado y revisar sus terminos de privacidad, tratamiento de datos, atribucion y SLA.

## Otros servicios opcionales

- Cloudflare Turnstile esta desactivado por defecto. Solo se consulta si `CODES_TURNSTILE_ENABLED=true` y se proporcionan claves. Al desactivarlo se mantienen los limites de intentos de la aplicacion; una instalacion publica debe usar proteccion anti-abuso equivalente en su proxy/WAF.
- SMTP es configurable y depende del proveedor que el operador elija (`CODES_SMTP_*`).
- No se cargan Google Fonts ni Leaflet desde CDN; tampoco se abre Google Maps. Leaflet esta empaquetado y la navegacion de coordenadas se mantiene dentro de CODES.
- `CODES_Prototipo.html` es una demo independiente: todavia carga Leaflet desde un CDN y usa teselas OSM y rutas OSRM publicas. No usarla como despliegue de produccion; requiere Internet y sus proveedores deben sustituirse si se opera esa demo.

## Verificacion antes de distribuir

El inventario no cubre todas las dependencias transitivas Maven/Python. Antes de publicar un artefacto, generar un SBOM (CycloneDX u otra herramienta equivalente) y revisar licencias y avisos de las dependencias.

Este repositorio aun no declara una licencia propia. Agregar una licencia para el codigo CODES requiere permiso y decision explicita de sus titulares.

## Uso de asistencia de IA

Se utilizo GitHub Copilot, un asistente de IA, para apoyar la revision y modificacion de la extraccion de ubicaciones, la configuracion de los servicios cartograficos y la actualizacion de esta documentacion. Las decisiones, pruebas y responsabilidad del proyecto corresponden a sus autores.
