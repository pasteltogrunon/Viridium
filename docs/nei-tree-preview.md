# Prototipo: árboles 3D en NEI

Rama: `codex/nei-tree-preview`.

## Uso

1. Arranca el cliente con `./gradlew.bat runClient` y entra en un mundo de prueba.
2. Abre el inventario, busca un bloque de Viridium en NEI y pulsa **R** con el cursor sobre él.
3. Selecciona la pestaña **Tree preview / Vista del árbol**. También está disponible con **U**.
4. Las flechas **< / >** recorren las variantes del árbol, con indicador de posición y disposición
   de retoños. Castaño americano y roble pedunculado tienen variantes **1×1 y 2×2**; el álamo blanco
   permite recorrer sus tres colores. Las flechas vuelven al principio/final al llegar a un extremo.
5. **New tree / Otro árbol** genera otro ejemplar de la variante seleccionada. **Pause / Pausar** detiene el giro.
6. Usa **+ / -** o la rueda sobre la vista 3D para cambiar el zoom.
7. Los iconos inferiores incluyen los bloques del árbol y su familia de madera. El tooltip indica
   cuántos bloques de ese tipo hay en el ejemplar, o si se trata de un bloque asociado que no aparece en él.

R se refiere a la consulta habitual de NEI sobre un **icono de inventario**, no a apuntar a un bloque
colocado en el mundo. Las recetas normales siguen disponibles en sus propias pestañas.

## Alcance

- Los conjuntos `LargeTreeBlockSet` reutilizan `TreeRegistry`, incluidos los generadores específicos
  del castaño americano y roble pedunculado en 2×2, el cocotero y el generador genérico para 1×1.
  Cada especie ocupa una única receta de NEI; sus variantes se descubren con `SaplingLayout` y
  `TreeRegistry.hasLayout`, usando el identificador completo del retoño (por ejemplo, `viridium:pedunculate_oak`).
- El suelo de previsualización se elige entre los suelos admitidos por el registro. Se comprueba
  cada posición de suelo de los layouts y se genera sin notificaciones a vecinos.
- Cambiar de variante conserva el zoom, la rotación y la semilla del ejemplar y actualiza los
  recuentos. Consultar directamente una hoja o retoño de álamo selecciona su color.
- Los árboles pequeños y las tres variantes del álamo blanco usan una forma provisional, indicada
  expresamente en pantalla, porque los generadores actuales no admiten esos conjuntos.
- Se conservan las coordenadas y metadatos del generador para dibujar orientación y conexiones.
- Los recuentos agrupan por bloque, sin separar las orientaciones del tronco. Son informativos;
  no representan una receta de fabricación ni los drops de talar el árbol.
- La generación ocurre una vez al mostrar el ejemplar y de nuevo al pulsar el botón, nunca por frame.
- Cada invocación del generador conserva su semilla local para evitar interferencias entre las
  vistas previas del cliente y la generación del servidor integrado.
- El mundo de vista previa es un buffer acotado, sin chunks, guardado, entidades ni actualizaciones
  hacia el mundo del jugador. El suelo virtual permite ejecutar las comprobaciones de los generadores.
- La integración se carga mediante `IConfigureNEI`; NEI sigue siendo opcional para el mod distribuido.
- El prototipo descubre los conjuntos y sus bloques mediante los campos públicos de `ViriBlocks`.
  Los conjuntos exclusivamente de madera, sin retoños/hojas, no tienen vista de árbol.

## Comprobación

`./gradlew.bat build` compila, comprueba formato/Checkstyle y produce el JAR normal.

`./gradlew.bat runClient -PtreePreviewSmokeTest` añade temporalmente al build un mod de diagnóstico
desde `src/previewTest/java`. Necesita OpenGL, espera al menú principal, carga los plugins reales de NEI,
comprueba las consultas por bloques, genera tres ejemplares de cada entrada y renderiza una muestra
de cada una. También comprueba metadatos, coordenadas negativas, eliminación de bloques y límites del
buffer, además de comparar generación secuencial y concurrente de los generadores deterministas.
También recorre las variantes mediante los mismos controles que las flechas, comprueba sus límites,
la regeneración y el borrado de recuentos antiguos. El render se prueba con el recorte OpenGL activo,
como lo utiliza NEI, y se verifica que el rectángulo de recorte se restaure.
Comprueba los tooltips antes y después de generar el árbol: cursor sobre una zona vacía, bloques
ajenos al árbol, bloques asociados y recuentos del ejemplar. Las líneas de tooltip existentes deben
conservarse. Para ejecutar la prueba sin compartir configuración con un cliente abierto, añade
`-PrunClientWorkingDirectory=run/nei-tooltip-smoke`.
Guarda capturas en `build/screenshots`, escribe `TREE_PREVIEW_SMOKE_OK` en el log y cierra
el cliente. Un fallo produce una excepción. Comprueba el marcador de éxito en el log: CoreTweaks
puede recuperar una excepción de interfaz aunque Gradle termine con código cero. Esta opción no debe usarse para empaquetar una versión
distribuible; ejecuta `build` sin la propiedad para obtener un JAR sin el mod de diagnóstico.

La prueba automatizada no sustituye estas comprobaciones de interacción:

- R y U sobre troncos, hojas, retoños, tablas, losas y variantes específicas.
- Regenerar y comprobar que los recuentos corresponden al nuevo ejemplar.
- Pausa, zoom y recorte de la vista con distintas escalas de GUI.
- Cambiar de pestaña y cerrar NEI: iluminación y profundidad deben seguir funcionando normalmente.
- Probar el mod en un cliente sin NEI y en un servidor dedicado.

El renderizado usa los renderizadores de bloques del cliente con el atlas de Minecraft y restaura
el estado OpenGL. Aún no incluye selección de bloques en 3D, capas, giro mediante arrastre ni una
caché de geometría para árboles especialmente grandes.

## Resultado de validación

- Compilación y verificaciones de Gradle completadas.
- Descubrimiento del plugin, registro de la pestaña y consulta real de NEI comprobados en el cliente.
- Tras integrar los layouts de master: **246 ejemplares generados y 82 variantes renderizadas**,
  incluyendo ambas formas del castaño y del roble, con navegación y recorte OpenGL comprobados.
- Capturas de castaño americano, roble pedunculado, cocotero y abeto negro inspeccionadas.
- La captura de ventanas de Windows falló por tiempo de espera: queda pendiente la comprobación
  manual de teclas, botones y distintas escalas de GUI dentro de un mundo.

## Regresión de tooltips (28 de octubre de 2026)

Los cinco informes de las 22:08–22:09 señalan una `NullPointerException` en
`TreePreviewHandler.handleItemTooltip`: NEI llama a ese método con `ItemStack == null` al pasar
el cursor por el fondo de la receta. Según dónde quede el cursor al abrir la pestaña, el fallo se
produce inmediatamente o tras moverlo. La prueba inicial sólo cubría generación y renderizado;
la regresión de tooltips añade este caso al diagnóstico del cliente.

La corrección devuelve el tooltip existente cuando no hay objeto bajo el cursor, cuando no pertenece
al árbol o cuando todavía no hay un ejemplar válido. La regresión reproduce el fallo con la versión
anterior y comprueba que los recuentos y las líneas de tooltip existentes siguen siendo correctos.

## Integración con master y variantes (30 de octubre de 2026)

Se integró `master` (`7b24269`) y se adaptó la previsualización a la nueva API de generadores,
identificadores y suelos. El buffer implementa `blockExists` para las comprobaciones de suelo sin
cargar chunks. La consulta del rectángulo OpenGL reserva los 16 enteros que exige LWJGL 2,
aunque `GL_SCISSOR_BOX` sólo devuelva cuatro valores. Se conserva el aspecto del render original.
