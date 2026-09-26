# Operacion local de CODES

Guia de instalacion, respaldo, seguridad y preparacion para una futura instalacion en LAN. CODES sigue funcionando localmente y no requiere dominio, HTTPS publico ni una base de datos externa.

## Inicio en Windows

La opcion recomendada para desarrollo y llamadas en vivo es:

```text
CODES.bat -> 1. Iniciar CODES completo
```

Ese modo busca o instala Java 21 y Maven, actualiza el diccionario de calles cuando corresponde, inicia Spring Boot y trata de iniciar el ASR local.

La aplicacion queda disponible en `http://localhost:8000` y el ASR local en `ws://localhost:6006`.

## Respaldo y restauracion

CODES.bat permite crear un respaldo local. Tambien se puede ejecutar:

```powershell
.\tools\backup_codes.ps1 -IncluirLogs
.\tools\restore_codes.ps1 -Respaldo .\backups\codes_YYYYMMDD_HHMMSS
```

CODES debe estar detenido para respaldar o restaurar la base H2. El respaldo incluye base de datos, audios, cache, diccionario, logs y secretos. Es informacion sensible y debe protegerse.

La perdida de `CODES_ENCRYPT_KEY` impide recuperar audios cifrados.

## Seguridad y evidencia

La aplicacion implementa JWT, BCrypt, roles, aislamiento institucional, invalidacion de sesiones, CORS restringido, limite de intentos, Turnstile, auditoria y cifrado AES-256-GCM.

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

HTTPS/WSS, integraciones oficiales, backup programado, PostgreSQL, migraciones de esquema y monitoreo externo pertenecen a la siguiente etapa de infraestructura.
