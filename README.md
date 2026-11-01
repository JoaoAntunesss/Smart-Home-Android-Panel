# Smart Hub
App Android (WebView) com a casa em 3D. A página está em `app/src/main/assets/index.html` (three.js incluído localmente).

## Obter o APK
1. Cria um repositório no GitHub e faz push desta pasta.
2. Separador **Actions** → "Build APK" → quando acabar, descarrega o artefacto **SmartHub-apk** (contém `app-debug.apk`).
3. Instala no telemóvel/tablet (permitir fontes desconhecidas).

Em alternativa, abre a pasta no Android Studio e corre **Build → Build APK(s)**.

## Ligar à Tuya (sem servidor)
A app fala diretamente com a Tuya Cloud (Central Europe, `openapi.tuyaeu.com`).
1. Abre a app e toca no **raio** (canto superior direito).
2. Cola o **Access ID**, o **Access Secret** e o **UID** do teu projeto Tuya e toca em *Ligar e carregar dispositivos*.
3. Associa cada aparelho da casa ao dispositivo da Tuya.

As chaves ficam guardadas só no armazenamento privado da app (não vão para o repositório).
