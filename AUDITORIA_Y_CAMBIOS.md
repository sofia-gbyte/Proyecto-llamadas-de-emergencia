# Auditoría y cambios implementados

## Implementado

- Aislamiento de llamadas por institución.
- La institución se asigna automáticamente desde el usuario creador de una llamada en vivo.
- Consultas de pendientes, en curso, cerradas y métricas filtradas por institución para usuarios no administradores.
- Administrador con supervisión global.
- Asignación y cierre con comprobación de institución.
- Un `operator` solo puede cerrar una llamada que tenga asignada.
- `supervisor` puede cerrar llamadas de su institución.
- `administrator` puede operar sobre todas las instituciones.
- Corrección de los nombres de roles usados por Spring Security (`operator`, `supervisor`, `administrator`).
- Corrección de las comprobaciones de rol del frontend.
- Validación de latitud/longitud del operador.
- Validación de audio: máximo 15 MB y whitelist de MIME/extensión.
- Endpoint autenticado `/api/auth/change-password`.
- Botón de cambio de contraseña en la interfaz.
- Contraseña inicial aleatoria generada por `tools/iniciar_codes_windows.ps1`; ya no existe `Admin123!` hardcodeado.
- El bootstrap de administrador no sobrescribe una cuenta existente.
- Backfill de institución para llamadas antiguas cuyo `createdByUser` permite identificarla.
- Tests unitarios iniciales para aislamiento y permisos de cierre.
- Límite multipart global ajustado a 15 MB por archivo y 16 MB por petición.
- README actualizado con la arquitectura y las nuevas medidas.

## Comprobaciones realizadas

- `node --check src/main/resources/static/script.js`: correcto.
- Búsqueda de usos antiguos de roles en JavaScript/backend: no quedan comparaciones funcionales con `administrador`/`operador` en español.
- Búsqueda de `Admin123!`: no queda contraseña fija en el proyecto.

## Pendiente de ejecutar en un entorno con Maven

Este entorno tiene Java 21, pero no tiene Maven instalado y no fue posible ejecutar `mvn test`. Antes de considerar esta versión verificada, ejecutar:

```powershell
mvn clean test
```

Y después probar manualmente el flujo completo: registro, activación, login, llamada en vivo, asignación, cierre, métricas, aislamiento entre instituciones y cambio de contraseña.

## Pendiente de producción

La auditoría no considera todavía CODES listo para producción. Siguen siendo fases posteriores: HTTPS/WSS, firewall/segmentación, migración H2 a una base de datos de producción, migraciones de esquema, backups/restauración, retención de datos, protección de logs, CSP/headers y revisión legal/privacidad.

### Corrección adicional de la revisión final (25-09-2026)
- Añadido `CallRepository.findByAssignmentDateIsNotNull()`, que faltaba y provocaba el error de compilación en `CallService.java`.
- `getInProgress()` ahora usa una lista mutable antes de ordenar, evitando `UnsupportedOperationException` con `Stream.toList()`.
- Corregidos los tests de `CallService` para usar autenticaciones Mockito/Spring Security marcadas como autenticadas.
- Verificación estática del frontend con `node --check`: OK.
- Verificación de integridad del ZIP: OK.
- Maven sigue sin estar disponible en este entorno, por lo que no se declara aquí una ejecución exitosa de `mvn clean test`.
