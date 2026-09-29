# Licencias, datos y servicios externos

Revision inicial del proyecto al 2026-09-29. Este documento identifica componentes visibles en el codigo y dependencias directas; no es asesoramiento legal ni sustituye la revision del artefacto final.

## Codigo y dependencias directas

| Componente | Version/uso | Licencia o terminos | Observaciones |
| --- | --- | --- | --- |
| Codigo CODES de este repositorio | Aplicacion | Sin licencia propia declarada | No asumir permiso de redistribucion o uso comercial del codigo del proyecto sin autorizacion de sus titulares. |
| Spring Boot y proyectos Spring | 3.3.4 | Apache License 2.0 | Revisar avisos de copyright en dependencias distribuidas. |
| H2 Database | Version resuelta por Spring Boot 3.3.4 | MPL 2.0 o EPL 1.0 | Confirmar la version exacta en el arbol de dependencias del release. |
| JJWT | 0.12.6 | Apache License 2.0 | Incluye modulos API, implementacion y Jackson. |
| Leaflet | 1.9.4, empaquetado como WebJar | BSD 2-Clause | El navegador obtiene los archivos desde el propio JAR de CODES; no necesita un CDN en ejecucion. |
| sherpa-onnx | Instalado por las herramientas ASR | Apache License 2.0 | La licencia del runtime no reemplaza la licencia del modelo. |
| Nemotron 3.5 ASR streaming 0.6B | El modelo se identifica desde su tarjeta de Hugging Face; `tools/asr` descarga el paquete publicado en releases de sherpa-onnx | OpenMDW-1.1 | La inferencia es local con sherpa-onnx; CODES no llama a una API de Hugging Face. La tarjeta indica disponibilidad para uso comercial, sujeto al acuerdo completo. |
| Nominatim | 5.3, opcional en `compose.maps.yaml` | GNU GPL-3.0 | Servicio autohospedado; si se redistribuye una imagen modificada, cumplir GPL y ofrecer el codigo fuente correspondiente. |
| OSRM | 26.9.0, opcional en `compose.maps.yaml` | BSD 2-Clause | Motor de rutas autohospedado. La imagen y sus dependencias tambien deben revisarse antes de redistribuir. |
| OpenStreetMap tile server | 2.3.0, opcional en `compose.maps.yaml` | Apache License 2.0 | Contenedor raster autohospedado. Las dependencias de la imagen y del estilo cartografico conservan sus propios terminos. |
| Docker Desktop | Instalacion opcional de desarrollo en Windows | Acuerdo propietario de Docker | No se distribuye con CODES. El uso sin costo depende de elegibilidad; el instalador muestra el acuerdo y no lo acepta automaticamente. |
| Docker Engine y Docker Compose | Recomendados para ejecutar el stack en Linux | Apache License 2.0 | Instalar directamente en Linux evita requerir Docker Desktop en el servidor. Las imagenes de contenedor mantienen sus licencias propias. |

Enlaces de referencia: [Spring Boot](https://github.com/spring-projects/spring-boot/blob/v3.3.4/LICENSE.txt), [H2](https://www.h2database.com/html/license.html), [JJWT](https://github.com/jwtk/jjwt/blob/0.12.6/LICENSE), [Leaflet](https://github.com/Leaflet/Leaflet/blob/v1.9.4/LICENSE), [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE), [tarjeta/licencia del modelo Nemotron](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b), [Nominatim](https://github.com/osm-search/Nominatim/blob/master/COPYING), [OSRM](https://github.com/Project-OSRM/osrm-backend/blob/master/LICENSE.TXT), [tile server](https://github.com/Overv/openstreetmap-tile-server/blob/master/LICENSE).

El JDK de ejecucion depende de la distribucion elegida (por ejemplo, Temurin u Oracle). Elegirla y revisar sus terminos en el servidor de produccion.

## Datos y servicios de mapas

| Elemento | Estado actual | Licencia/condiciones |
| --- | --- | --- |
| Datos cartograficos, diccionario de calles y cache de geocodificacion derivados de OpenStreetMap | OSM se usa para el mapa, `data/calles_chile.txt` y los resultados cacheados | ODbL 1.0; mostrar atribucion a OpenStreetMap y sus contribuidores. Las bases derivadas pueden tener obligaciones share-alike. |
| Teselas del mapa | OSM publico por defecto; servidor propio en `compose.maps.yaml`, configurable con `CODES_MAP_TILES_URL` | Los datos OSM son abiertos, pero el servidor publico tiene politica separada y no ofrece un servicio offline/API de produccion con SLA. |
| Geocodificacion | Nominatim publico y Photon publico por defecto; el stack puede autohospedar Nominatim y Photon se puede omitir dejando `CODES_PHOTON_BASE_URL` vacio | Los servicios publicos tienen politicas independientes de la licencia de datos. El stack Nominatim propio es GPL-3.0. |
| Rutas | OSRM publico por defecto; stack propio disponible en `compose.maps.yaml`, configurable con `CODES_ROUTING_URL` | El servidor demo no ofrece garantia de disponibilidad. OSRM es BSD 2-Clause; los datos de red siguen bajo ODbL. |
| Geolocalizacion del operador | API de geolocalizacion del navegador, solo bajo accion del usuario | La posicion depende del dispositivo/OS y permisos del navegador; no se envia como posicion del incidente. |

Atribucion OSM requerida en el mapa: `© OpenStreetMap contributors`, enlazada a [openstreetmap.org/copyright](https://www.openstreetmap.org/copyright). Consultar tambien las politicas de [teselas](https://operations.osmfoundation.org/policies/tiles/) y [Nominatim](https://operations.osmfoundation.org/policies/nominatim/). El servicio publico de Overpass se utiliza para descargar/actualizar el diccionario, no durante el flujo normal de llamadas.

## Otros servicios opcionales

- Cloudflare Turnstile esta desactivado por defecto. Solo se consulta si `CODES_TURNSTILE_ENABLED=true` y se proporcionan claves. Al desactivarlo se mantienen los limites de intentos de la aplicacion; una instalacion publica debe usar proteccion anti-abuso equivalente en su proxy/WAF.
- SMTP es configurable y depende del proveedor que el operador elija (`CODES_SMTP_*`).
- No se cargan Google Fonts ni Leaflet desde CDN; tampoco se abre Google Maps. Leaflet esta empaquetado y la navegacion de coordenadas se mantiene dentro de CODES.
- `CODES_Prototipo.html` es una demo independiente: todavia carga Leaflet desde un CDN y usa teselas OSM y rutas OSRM publicas. No usarla como despliegue de produccion; requiere Internet y sus proveedores deben sustituirse si se opera esa demo.

## Docker en Windows y Linux

Segun el acuerdo consultado el 2026-09-29, Docker Desktop se ofrece sin costo para uso personal, educativo individual y proyectos open source no comerciales. Para empresas, el umbral publicado exige simultaneamente menos de 250 empleados y menos de USD 10 millones de ingresos anuales; entidades gubernamentales y organizaciones fuera de esos limites requieren suscripcion. El acuerdo tambien distingue uso educativo individual de uso institucional coordinado. Confirmar siempre el caso real en el [acuerdo vigente de Docker](https://www.docker.com/legal/docker-subscription-service-agreement/) y en [precios](https://www.docker.com/pricing/).

El proyecto no acepta ni redistribuye Docker Desktop. Su instalador de Windows solicita confirmacion y deja la aceptacion del acuerdo al usuario. Docker Engine y Compose instalados directamente en Linux son Apache-2.0; las imagenes y servicios OSM/Nominatim/OSRM tienen licencias adicionales listadas arriba.

## Verificacion antes de distribuir

El instalador de ASR descarga el paquete desde [releases de sherpa-onnx en GitHub](https://github.com/k2-fsa/sherpa-onnx/releases); su README identifica el modelo original en Hugging Face. CODES no usa endpoints de Hugging Face para inferencia. El inventario no cubre todas las dependencias transitivas Maven/Python ni cada paquete de las imagenes Docker. Antes de publicar un artefacto, generar un SBOM (CycloneDX u otra herramienta equivalente), revisar licencias y avisos de todas las imagenes, y fijar versiones/huellas del runtime ASR, modelo y extracto de datos.

Este repositorio aun no declara una licencia propia. Agregar una licencia para el codigo CODES requiere permiso y decision explicita de sus titulares.

## Uso de asistencia de IA

Se utilizo GitHub Copilot, un asistente de IA, para apoyar la revision y modificacion de la extraccion de ubicaciones, la configuracion de los servicios cartograficos y la actualizacion de esta documentacion. Las decisiones, pruebas y responsabilidad del proyecto corresponden a sus autores.
