# Análisis de color de bloques

En un mundo, ejecuta `/blockcolors oklab`. Aparece una nube 3D delante del jugador,
que puedes explorar volando en creativo. Es una visualización local: no coloca bloques,
no guarda cambios en el mundo y también funciona como herramienta de cliente en multijugador.
Rama de experimentación: `pruebas`.

## Comandos

| Comando | Resultado |
| --- | --- |
| `/blockcolors rgb` | Ejes X/Y/Z = R/G/B de sRGB, de 0 a 1. |
| `/blockcolors oklab` | X/Y/Z = a/L/b; la claridad crece hacia arriba. |
| `/blockcolors contrast` | X/Y/Z = croma OKLab / claridad L / desviación estándar de L. |
| `/blockcolors axes a l contrast` | Combina libremente tres métricas. |
| `/blockcolors scale 64` | Longitud de cada eje, de 8 a 192 bloques; inicialmente 48. |
| `/blockcolors size 0.6` | Tamaño de las muestras, de 0.1 a 4 bloques. |
| `/blockcolors spread 2` | Dispersión determinista de hasta ±1 bloque por eje para separar solapamientos. |
| `/blockcolors spread 0` | Posiciones exactas, sin dispersión (valor inicial). |
| `/blockcolors filter viridium:` | Filtra por fragmento del identificador o del nombre del bloque. |
| `/blockcolors filter minecraft:wool` | Muestra las variantes de lana. |
| `/blockcolors filter all` | Quita el filtro. |
| `/blockcolors refs on` / `off` | Muestra/oculta ejes, marcas y nueve colores de referencia. |
| `/blockcolors here` | Reubica la nube delante del jugador. |
| `/blockcolors info` | Escribe en el chat las métricas de la muestra señalada. |
| `/blockcolors next` | Recorre muestras que se solapan bajo el cursor. |
| `/blockcolors status` | Progreso, número de muestras y variantes omitidas. |
| `/blockcolors off` / `on` | Oculta/recupera la nube; `off` también cancela un análisis en curso. |
| `/blockcolors rescan` | Repite el análisis del atlas actual. |
| `/blockcolors help` | Ayuda y autocompletado con Tab. |

Ejes disponibles: `r`, `g`, `b`, `l`, `a`, `lab_b`, `chroma`, `contrast`.
`b` es el canal azul de RGB; `lab_b` es el eje azul–amarillo de OKLab.
Los rangos son fijos: RGB y L [0,1], a y b [-0.4,0.4], croma [0,0.4], contraste [0,0.5].
Así, filtrar muestras no modifica la escala ni las posiciones de las demás.
La lectura RGB del HUD y de `info` usa la escala familiar de 0 a 255.

## Qué se mide

- Se recorre el registro de bloques completo, con las variantes que cada bloque anuncia
  mediante `getSubBlocks`. Se convierten los daños del objeto a metadatos de colocación,
  se eliminan duplicados y se incluye metadata 0 para bloques sin variantes creativas.
  No se inventan combinaciones de orientación ni estados arbitrarios de entidades de bloque/NBT.
- Se lee una instantánea del nivel base del atlas que está usando el cliente. Esto incluye
  el pack de recursos activo, texturas generadas y los recortes de iconos de Viridium.
  En texturas animadas se analiza el fotograma que estaba cargado al iniciar el análisis;
  las muestras visibles siguen usando el atlas animado. `rescan` permite medir otro instante.
- Cada una de las seis caras tiene la misma superficie, independientemente de la resolución.
  Los píxeles se ponderan por alfa; los completamente transparentes no cuentan. Se aplica
  el tinte de inventario `getRenderColor(meta)` antes del cálculo.
- RGB es la media de los canales sRGB. OKLab es la media de las conversiones de cada píxel
  a OKLab, con sRGB linealizado. **No** es la conversión de la media RGB. El croma es
  `sqrt(media(a)^2 + media(b)^2)`.
- El contraste es la desviación estándar poblacional, ponderada por alfa, de la claridad
  OKLab L de todos los píxeles de las caras. Incluye las diferencias entre caras. Una textura
  uniforme da cero; una mezcla a partes iguales de negro y blanco da aproximadamente 0.5.
  La conversión usa las [matrices de OKLab de Björn Ottosson](https://bottosson.github.io/posts/oklab/).

## Alcance visual y rendimiento

Las muestras son **cubos de texturas**, sin iluminación ambiental, con las seis caras del bloque.
No reproducen geometrías especiales como escaleras, plantas, TESR, capas de tintes o modelos
procedurales. El tinte de inventario es una aproximación para bloques que varían por bioma,
posición o cara. Los bloques sin iconos utilizables se omiten y el contador lo indica; los
fallos individuales se registran en nivel debug. Una cara sin icono queda sin dibujar.

El escaneo se reparte entre frames (presupuesto aproximado de 6 ms, entre bloques), reutiliza
estadísticas de caras iguales y libera la referencia al buffer al terminar. Leer inicialmente
el atlas requiere una transferencia GPU→CPU y puede provocar una pausa. El buffer temporal
se limita a 256 MiB (por ejemplo, un atlas 8192×8192); packs superiores requieren bajar resolución.
El dibujo agrupa los cubos en una pasada y descarta los que están fuera del campo de visión.
La selección alcanza 256 bloques y se realiza sobre la nube, independientemente del terreno.
Los elementos idénticos pueden coincidir exactamente: usa `next`, filtros o `spread`.
La recarga de recursos vuelve a analizar el atlas; salir del mundo borra la nube y su caché.

## Comprobación

`./gradlew.bat build` incluye pruebas JUnit: referencias numéricas de negro/blanco/primarios,
linealización sRGB, diferencia entre espacios de promediado, transparencia, tintes,
ponderación por alfa, contraste, combinación de caras de distinta resolución y escalas de ejes.

`./gradlew.bat runClient -PcolorAnalysisSmokeTest -PrunClientWorkingDirectory=run/color-smoke`
añade un mod de diagnóstico temporal. Crea un mundo plano de prueba, ejecuta los comandos
reales, comprueba las 16 lanas y los bloques de Viridium, toma capturas de los tres modos,
prueba filtros y ejes, y repite el análisis tras recargar recursos. Escribe
`COLOR_ANALYSIS_SMOKE_OK` antes de cerrar. Necesita OpenGL. Compila el JAR distribuible sin esa
propiedad para excluir el diagnóstico. Las capturas se guardan en `build/screenshots`.

Validado en el cliente de desarrollo: **1.229 bloques, 1.364 muestras y 0 omitidas**.
La prueba también comprueba la selección de una lana con el cursor y la conservación
del estado OpenGL. `build`, JUnit, formato y Checkstyle completados correctamente.
Queda por comprobar el comportamiento con otros packs de recursos y el catálogo completo de GTNH.
