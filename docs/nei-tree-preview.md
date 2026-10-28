# Prototipo: árboles 3D en NEI

Rama: `codex/nei-tree-preview`.

## Uso

1. Arranca el cliente con `./gradlew.bat runClient` y entra en un mundo de prueba.
2. Abre el inventario, busca un bloque de Viridium en NEI y pulsa **R** con el cursor sobre él.
3. Selecciona la pestaña **Tree preview / Vista del árbol**. También está disponible con **U**.
4. **New tree / Otro árbol** genera otro ejemplar. **Pause / Pausar** detiene el giro.
5. Usa **+ / -** o la rueda sobre la vista 3D para cambiar el zoom.
6. Los iconos inferiores incluyen los bloques del árbol y su familia de madera. El tooltip indica
   cuántos bloques de ese tipo hay en el ejemplar, o si se trata de un bloque asociado que no aparece en él.

R se refiere a la consulta habitual de NEI sobre un **icono de inventario**, no a apuntar a un bloque
colocado en el mundo. Las recetas normales siguen disponibles en sus propias pestañas.

## Alcance

- Los conjuntos `LargeTreeBlockSet` reutilizan `TreeRegistry`, incluidos los generadores específicos
  del castaño americano, roble pedunculado y cocotero, y el generador genérico del resto.
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
Guarda capturas en `build/screenshots`, escribe `TREE_PREVIEW_SMOKE_OK` en el log y cierra
el cliente. Un fallo produce una excepción. Esta opción no debe usarse para empaquetar una versión
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
- 240 ejemplares generados y 80 entradas renderizadas, incluidos los troncos pequeños con conexiones.
- Capturas de castaño americano, roble pedunculado, cocotero y abeto negro inspeccionadas.
- La captura de ventanas de Windows falló por tiempo de espera: queda pendiente la comprobación
  manual de teclas, botones y distintas escalas de GUI dentro de un mundo.
