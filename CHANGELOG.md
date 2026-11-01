# Historial de cambios

Este archivo resume los cambios funcionales y de operación relevantes por versión de CODES. Las versiones de las bibliotecas se mantienen documentadas por separado en [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) y en `pom.xml`.

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
