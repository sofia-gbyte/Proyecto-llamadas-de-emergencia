# Plantilla de referencia. Reemplaza cada valor antes de usarla como
# data\.codes-secrets.ps1. Nunca escribas secretos reales en este archivo.
$env:CODES_JWT_SECRET = '<secreto aleatorio de al menos 32 bytes>'
$env:CODES_ENCRYPT_KEY = '<clave AES-256 en Base64, generada con 32 bytes aleatorios>'
$env:CODES_ADMIN_USER = 'admin'
$env:CODES_ADMIN_PASSWORD = '<contraseña inicial única y robusta>'
$env:CODES_DB_USERNAME = 'sa'
$env:CODES_DB_PASSWORD = ''

# Opcionales: sin estas claves, transcripción y geocodificación automáticas
# quedan deshabilitadas; el resto de la aplicación puede iniciar.
$env:GROQ_API_KEY = ''
$env:GEOAPIFY_API_KEY = ''
$env:GEOAPIFY_PLACES_API_KEY = ''
$env:GEOAPIFY_ROUTING_API_KEY = ''
$env:GEOAPIFY_MAPS_API_KEY = ''
$env:CODES_MAP_PROVIDER = 'esri'
$env:CODES_ROUTING_PROVIDER = 'osrm'
$env:CODES_ALTCHA_ENABLED = 'true'
$env:CODES_ALTCHA_SECRET = ''

# Opcionales, para recuperación de contraseña por correo.
$env:CODES_SMTP_HOST = ''
$env:CODES_SMTP_PORT = '587'
$env:CODES_SMTP_USERNAME = ''
$env:CODES_SMTP_PASSWORD = ''
$env:CODES_MAIL_FROM = ''
