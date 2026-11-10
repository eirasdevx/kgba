# KGBA: emulador de Game Boy Advance en Kotlin para Android

Es un emulador de GBA escrito desde cero en Kotlin. El núcleo es Kotlin puro (sin Android), así que también se puede probar en el PC. Encima va una app Android con controles táctiles, mando físico, sonido, partidas guardadas y estados guardados.

## Estructura

```
core/      Núcleo del emulador (módulo Kotlin/JVM, sin dependencias)
  Cpu.kt     ARM7TDMI: ARM + Thumb, bancos de registros, excepciones, pipeline de 2 etapas
  Bus.kt     Mapa de memoria, open bus, waitstates (WAITCNT) y prefetch aproximado
  Ppu.kt     Gráficos por línea: modos 0-5, sprites normales y afines, ventanas, blending, mosaico
  Apu.kt     Sonido: 4 canales PSG + Direct Sound A/B (FIFO por DMA), 32768 Hz estéreo
  Dma.kt     4 canales DMA: inmediato, VBlank, HBlank, FIFO de sonido
  Timers.kt  4 temporizadores con prescaler y cascada
  Backup.kt  Guardado del cartucho: SRAM, Flash 64K/128K y EEPROM 512B/8K (autodetección)
  Sio.kt     Puerto serie y cable link entre dos consolas
  Gpio.kt    Hardware del cartucho: reloj RTC, sensor solar, giroscopio, vibración e inclinación
  Hle.kt     BIOS de alto nivel: las SWI de la BIOS en Kotlin (no necesitas ningún archivo de BIOS)
  Gba.kt     La consola: planificador por líneas, E/S, interrupciones y estados guardados
app/       App Android (minSdk 24)
desktop/   Reproductor de escritorio (Swing) y ejecutor sin ventana para pruebas
```

## Compilar la app Android

1. Abre la carpeta `KGBA` con **Android Studio** (Ladybug o posterior). Él descarga Gradle, el plugin de Android y el SDK 35.
2. Conecta el móvil con la depuración USB activada.
3. **Importante, por rendimiento:** instala la variante **release** (Build ▸ Select Build Variant ▸ `release`).
   En modo *debug* Android ejecuta el código mucho más despacio y el emulador puede ir a menos de 60 fps.
   La variante release ya está configurada para firmarse con la clave de depuración, así que se instala sin más.

Desde la terminal también funciona: `./gradlew :app:installRelease`.

## Uso

- **Biblioteca y ajustes**: tema oscuro con acentos azules, cabecera compacta y lista de juegos recientes. Toca un juego para abrirlo; el botón ⋮ muestra sus opciones y + importa una ROM. Los ajustes permiten personalizar la consola, gestionar la BIOS y cambiar sonido, carcasa, botones y rebobinado.

- **Carátulas**: la biblioteca muestra la caja original de cada juego. La ROM se identifica por CRC32, código de cartucho o nombre con la base No-Intro de libretro, y la imagen se descarga de las miniaturas de libretro (reducida a 320 px en JPEG). Sin conexión se reintenta más tarde. En ⋮ ▸ «Buscar carátula de nuevo».
- **Carcasa KGBA Clásica** (la predeterminada): dibujada en código, sin imágenes. Tiene cuerpo con degradado y canto brillante, marco oscuro con reflejo, logotipo y LED, rejilla de altavoz y botones en relieve que se hunden al pulsarlos. Los colores de la carcasa y de los botones se eligen en «Personalizar consola». En horizontal el marco es más fino para no restar tamaño al juego.
- **Carcasas en imágenes**: Ajustes ▸ Carcasa ▸ Importar (.zip). Acepta una carcasa de piezas sueltas (`bg_portrait_*.png`, `screen_gba.png`, `a.png`, `cross_ul.png`…) o un paquete con varias en .zip dentro; en ese caso se eligen cuáles y se completan con su `-Shared`. La app no incluye ninguna: se copian a su carpeta privada.
- **Abrir ROM**: acepta `.gba` y también `.zip` que contengan un `.gba`.
- **BIOS (opcional)**: si tienes un volcado de tu propia consola (16 KB), se usa la BIOS real y verás el logo de arranque. Sin BIOS, el emulador usa su BIOS en Kotlin, que basta para la gran mayoría de juegos.
- **Partida guardada**: se guarda sola (cada ~5 s si hay cambios, y al pausar o salir).
- **Pantalla**: carcasa de color plano, marco fino y controles sin relieves. En vertical, la pantalla 3:2 está arriba, los controles en el centro y los accesos rápidos abajo. En horizontal, la imagen completa se amplía hasta el espacio disponible entre los controles laterales, sin deformarla. Las barras ocultas no reservan espacio y se respetan las muescas de la cámara. Se puede ocultar la carcasa desde el menú.
- **Personalizar consola**: disponible en Ajustes y en el menú de pausa → Controles. El editor usa el tamaño real de la consola: arrastra la cruceta, A/B, L/R, Start/Select o los accesos rápidos. También puedes seleccionar un control por su nombre y ajustar su tamaño entre el 65 % y el 150 %. El área del juego queda protegida.
- **Colores y diseños**: elige colores de carcasa y botones en la paleta o introduce un valor #RRGGBB. Gira el dispositivo para editar vertical y horizontal por separado. «Guardar» aplica ambos diseños; «Cancelar» permite descartar el borrador; «Restablecer» afecta solo a las posiciones y tamaños de la orientación actual. Los diseños se conservan al cerrar la app.
- **Barra de herramientas**: menú, rebobinar (mantener), guardar, ranura actual, cargar y avance rápido.
- **Estados guardados**: 10 ranuras por juego con miniatura y fecha. El guardado/carga rápidos usan la ranura actual (tócala para cambiarla).
- **Menú de pausa** (≡ o atrás): ranuras, avance rápido (×2, ×3, ×4, ×6 o sin límite), rebobinado (15 s), filtros (nítido, suavizado, líneas de barrido, rejilla LCD), colores de pantalla de GBA, captura de pantalla (se guarda en Imágenes/KGBA), sonido, botones táctiles, opacidad, asignación de botones del mando, reiniciar.
- **Mando físico o teclado**: todo se puede reasignar desde el menú. Por defecto: cruceta o stick, A/B, L1/R1, Start/Select; L3 = guardado rápido, R3 = carga rápida, L2 = rebobinar, R2 = avance rápido. Teclado: WASD, K/X = A, J/Z = B, Q/E = L/R, Enter = Start, Espacio = Select, F5/F8 = guardar/cargar, Tab = rápido, R = rebobinar.
- **Multijugador (cable link)**, menú ▸ Multijugador, para 2 jugadores:
  - *Red local*: un móvil elige «Crear partida en la red local» y el otro «Unirse»; la partida aparece sola en la lista (o escribe la IP). Sirve la misma Wi-Fi o la zona Wi-Fi de uno de los móviles.
  - *Bluetooth*: empareja antes los dos móviles en los ajustes; uno crea la partida y el otro elige el móvil de la lista.
  - Quien crea la partida es el jugador 1. Funcionan el modo multijugador (Pokémon, Mario Kart, etc.) y el modo normal de 8/32 bits. Con el cable conectado se desactiva el rebobinado.
- **Hardware del cartucho**: reloj RTC con la hora del móvil (Pokémon Rubí/Zafiro/Esmeralda, Boktai...), sensor solar de Boktai (con el sensor de luz del móvil o un nivel fijo), giroscopio de WarioWare: Twisted!, inclinación de Yoshi Topsy-Turvy / Koro Koro Puzzle (acelerómetro) y vibración (WarioWare: Twisted!, Drill Dozer).

Usa solo ROMs que hayas extraído de tus propios cartuchos, o juegos homebrew.

## Probar el núcleo en el PC

```bash
./gradlew :desktop:run --args="/ruta/juego.gba"
# Flechas, X=A, Z=B, A=L, S=R, Enter=Start, Retroceso=Select, Tab=rápido, F5/F8=estado

./gradlew :desktop:headless --args="juego.gba 600 captura.png"   # sin ventana
```

## Estado de las pruebas

Con la BIOS en Kotlin pasan todos los tests de [jsmolka/gba-tests](https://github.com/jsmolka/gba-tests): `arm`, `thumb`, `memory`, `bios`, `nes`, `unsafe`, `save/sram`, `save/flash64`, `save/flash128` y `save/none`. Los de `ppu` se ven bien. Con una BIOS real (la libre de Cult-of-GBA) arranca el logo y pasan `arm`, `thumb` y `memory`. El test `bios` espera valores propios de la BIOS original, así que con la de Cult-of-GBA no aplica.

También se ha probado entero el juego homebrew **µCity Advance**: menús, mapa con scroll, sprites, ventanas, música por Direct Sound y estados guardados deterministas.

## Limitaciones conocidas e ideas para seguir

- Temporización aproximada: el tiempo de los DMA ya se descuenta a la CPU, pero los waitstates y el prefetch siguen siendo aproximados y no hay precisión de ciclo.
- Cable link: máximo 2 jugadores, sin adaptador inalámbrico ni modo UART/JOY Bus. Cada transferencia espera la respuesta del otro móvil, así que el enlace va más lento que un cable real (más por Bluetooth que por Wi-Fi); los juegos lo toleran, pero los intercambios tardan algo más. Si un jugador abre el menú, el otro se queda esperando.
- El sentido de los ejes de inclinación y giroscopio no se ha probado con los juegos reales; si va al revés habría que invertir el signo en `EmulatorActivity.onSensorChanged`.
- Rendimiento: los fondos de texto ya se leen por tile; el siguiente paso sería decodificar las instrucciones de la CPU por bloques.

## Verificación de la interfaz personalizable

`./gradlew :app:assembleRelease :app:lintDebug` compila y analiza la app.
Con un dispositivo o emulador Android conectado, `./gradlew :app:connectedDebugAndroidTest`
comprueba arrastre, tamaño, entrada táctil, cancelación, separación por orientación,
serialización, restablecimiento y apertura del editor, sin necesitar una ROM externa.
También comprueba que la consola no tape ningún píxel del interior del juego en seis tamaños,
con y sin carcasa, y ejecuta una ROM de prueba propia para verificar los cuatro bordes de la
imagen al pasar por vertical, horizontal, horizontal invertido y volver a vertical.
Las capturas de la consola vertical, horizontal y del editor se guardan en el directorio
externo de archivos de la app en el dispositivo de pruebas.

Verificación del rediseño: compilación release, lint y pruebas instrumentadas completadas en
Android 11 (API 30). Se revisaron capturas de biblioteca, ajustes, consola y editor en vertical
y horizontal. La prueba comprueba también que el panel del editor queda dentro del marco
tras abrirse y rotar. Esto no sustituye las pruebas de rendimiento con juegos en un móvil real.

El wrapper usa Gradle 8.14.3; para compilar desde terminal usa JDK 17.
