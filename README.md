# OpenBeam

Proyecto Android Studio para:
- tocar dos teléfonos con NFC,
- leer un token con HCE,
- arrancar Nearby Connections sin internet,
- mandar fotos por transferencia local,
- activar el modo desde un tile del Quick Settings.

## Árbol
Ver el árbol en la respuesta del chat.

## Compilar APK
1. Abre Android Studio.
2. Elige **Open** y abre esta carpeta.
3. Espera a que Gradle termine de sincronizar.
4. Conecta un teléfono con depuración USB.
5. Ve a **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
6. Android Studio te enseña la ruta del APK generado.

## Flujo
- Botón **Modo enviar**: activa Nearby como emisor.
- Botón **Modo recibir**: activa NFC reader mode y luego Nearby.
- **Elegir foto**: copia la imagen al caché.
- **Mandar foto**: manda metadata + archivo.
- **Tile**: activa o apaga el modo listo desde QS.

## Nota
Nearby Connections no usa internet; usa tecnologías locales como Bluetooth y Wi-Fi para el enlace cercano.
