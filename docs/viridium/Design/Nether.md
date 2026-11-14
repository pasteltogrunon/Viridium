# Nether surrealista: diseño de generación coherente para Cubic Chunks

**Versión:** 0.1 · 14 de noviembre de 2026  
**Estado:** base conceptual para desarrollo y prototipado.  
**Alcance:** identidad del mundo, capas de generación, relaciones entre sistemas, navegación y criterios de evaluación. No prescribe algoritmos ni arquitectura de código.

## 1. Visión del proyecto

Crear un Nether tridimensional, surrealista y ligeramente liminal, cuya geografía proceda de estructuras mayores que interactúan entre sí. La coherencia global debe ser tan importante como en el Overworld de Viridium 2.0, aunque las leyes que la producen sean ficticias.

La identidad de cada dimensión sirve como referencia artística:

| Dimensión | Identidad principal |
| --- | --- |
| Overworld | Un lugar físico, interpretable mediante procesos naturales |
| End | Un lugar desértico, marcado por el vacío y la desolación |
| Twilight | Un lugar mágico |
| Nether | Un lugar surrealista, abstracto y ligeramente liminal |

El Nether puede combinar interior planetario, mundo que crece y se consume e inframundo de dominios. La roca, la lava, los organismos y las anomalías espaciales deben poder formar parte de una misma explicación del paisaje.

**Experiencia buscada:** el jugador observa un paisaje extraño, reconoce señales, formula una hipótesis sobre lo que hay cerca y elige una ruta. Al explorar descubre que esas señales pertenecían a una estructura mayor.

La familiaridad con el mundo debe mejorar la orientación. La sorpresa debe surgir de nuevas combinaciones y situaciones excepcionales dentro de sus reglas.

## 2. Decisiones de partida y propuestas abiertas

### 2.1. Bases acordadas

- El surrealismo y la abstracción definen la identidad del Nether, con un toque liminal moderado.
- La generación se organiza en varias capas relacionadas, como en el Overworld de Viridium.
- Existen grandes núcleos y conexiones principales entre ellos.
- Los núcleos originan dominios que cumplen una función equivalente a los continentes.
- Cada núcleo depende de variables compartidas con el mundo y constituye un landmark importante.
- El núcleo concentra la máxima expresión del surrealismo de su dominio.
- La verticalidad es una variable importante y debe poder recorrerse con comodidad razonable.
- Las chimeneas facilitan la conexión entre alturas.
- La lava tiene un papel estructural y visual fundamental.
- Los ecosistemas pueden ocupar suelo, paredes y techo.
- La apariencia del terreno debe ayudar a encontrar lugares y recursos.
- Las categorías finales de paisaje proceden de estructuras mayores; no son la causa inicial del terreno.

### 2.2. Propuestas de este documento

Los nombres de variables, las familias de transformaciones, los ejemplos de paisajes y la secuencia de prototipado son propuestas de trabajo. Sirven para concretar el diseño y pueden cambiar conservando las bases anteriores.

También quedan abiertas la extensión vertical del mundo, las escalas exactas, el catálogo de materiales, las recompensas de los núcleos y la adaptación del contenido existente del Nether.

## 3. Qué trasladamos de Viridium 2.0

La referencia es la lógica de relaciones del generador del Overworld: distintas capas contribuyen al mismo paisaje y dejan señales compatibles entre sí.

| Función en el Overworld | Equivalente propuesto en el Nether |
| --- | --- |
| Las placas organizan grandes alineaciones y contrastes | Un sustrato profundo organiza fracturas, tendencias espaciales y condiciones de los núcleos |
| Los continentes proporcionan identidad y escala regional | Los dominios de influencia proporcionan identidad volumétrica |
| El relieve distribuye barreras, valles y pasos | La arquitectura de roca y vacío distribuye cámaras, macizos, abismos y chimeneas |
| Los ríos relacionan lugares y ayudan a orientarse | Las rutas principales y las redes de lava relacionan lugares con funciones distintas |
| La geología explica materiales y formas | El sustrato y las transformaciones explican materiales y formas |
| El clima condiciona los ecosistemas | La actividad, la influencia, la lava y las superficies disponibles condicionan los ecosistemas |
| Los biomas resumen condiciones previas | Los paisajes y ecosistemas resumen condiciones previas |

Las equivalencias son funcionales. Un dominio no necesita parecer un continente, ni una autopista necesita comportarse como un río.

La principal diferencia es que el Nether se organiza como un volumen. Encima y debajo de una región puede haber otras regiones relevantes, y su relación forma parte de la geografía.

## 4. Principios de diseño

### Coherencia causal

Las decisiones importantes deben tener consecuencias en varios sistemas. Una fractura puede orientar un corredor, conducir lava y exponer materiales. Esa relación es más valiosa que tres detalles independientes.

### Lectura a varias escalas

El mundo debe funcionar a escala de dominio, de trayecto y de superficie. Las formas pequeñas deben reforzar las direcciones y propiedades de las grandes.

### Reglas extrañas con consecuencias estables

Una influencia puede plegar paredes o producir masas suspendidas. El jugador debe poder reconocer cómo se manifiesta y cómo cambia al acercarse a su origen.

### Contraste y descanso

La monumentalidad necesita espacios contenidos; la saturación necesita zonas vacías. La intensidad visual constante borraría la jerarquía de los lugares.

### Navegación como parte del terreno

Los recorridos principales se diseñan junto con las formas que los contienen. La decoración posterior debe conservar su utilidad.

### Continuidad entre regiones

Las formas y redes pueden cruzar fronteras. Los dominios no deben sentirse como habitaciones independientes pegadas entre sí.

### Desarrollo por relaciones comprobables

Antes de ampliar el catálogo de contenido, debe verificarse que una pequeña cantidad de estructuras produce orientación, variedad y coherencia.

## 5. Modelo general de capas

La secuencia conceptual propuesta es:

1. **Sustrato profundo:** grandes estructuras y condiciones anteriores a los núcleos.
2. **Núcleos:** focos con propiedades derivadas de ese sustrato.
3. **Dominios de influencia:** propagación e interacción de sus transformaciones.
4. **Arquitectura regional:** distribución de roca, cavidades y barreras.
5. **Conexiones:** rutas principales, pasos y chimeneas.
6. **Lava:** fuentes, reservorios, cauces y huellas de actividad.
7. **Materiales y superficies:** expresión concreta de las condiciones anteriores.
8. **Ecosistemas, estructuras y detalle:** ocupación de los espacios disponibles.

Las capas pueden compartir información sin convertirse en sistemas circulares. Por ejemplo, el potencial térmico inicial ayuda a definir un núcleo; más adelante, la lava concreta modifica las condiciones locales de una pared. Son dos momentos distintos del diseño.

La historia del lugar puede representarse mediante formas estáticas: canales secos, costras rotas, ruinas enterradas o núcleos apagados. Esta propuesta no requiere que los continentes cambien o que los núcleos transformen el terreno durante la partida.

## 6. Sustrato profundo: la estructura anterior a los dominios

El sustrato proporciona continuidad a escalas mayores que un núcleo. Puede entenderse como una tectónica ficticia del espacio.

Sus componentes iniciales podrían ser:

| Componente | Función | Huellas posibles |
| --- | --- | --- |
| Fracturación | Organizar alineaciones, pasos y barreras | Grietas, paredes paralelas, cámaras alargadas |
| Potencial térmico | Distribuir posibilidades de actividad y lava | Cambios de material, costras, fisuras luminosas |
| Deformación espacial | Definir tendencias de orientación y plegamiento | Estratos curvos, inclinaciones, formas elongadas |
| Sustrato material | Determinar cómo responde la materia | Distintas formas de romperse, crecer o vaciarse |
| Antigüedad y actividad | Diferenciar formación, estabilidad y abandono | Superposición de formas recientes y antiguas |

Estos componentes deben poder variar de forma parcialmente independiente. Una región antigua puede reactivarse; un material puede atravesar varios dominios; una gran fractura puede continuar entre núcleos distintos.

El sustrato no necesita dividir el mundo en provincias cerradas. Puede contener gradientes, bandas y alineaciones extensas. Su finalidad es explicar por qué ciertas cosas aparecen juntas y por qué esa relación continúa más allá del lugar visible.

## 7. Núcleos: origen de los dominios y landmarks

### 7.1. Función

Un núcleo concentra una forma de transformación del mundo. Su posición y sus propiedades dependen del sustrato; su influencia genera el dominio circundante.

Esto establece una relación clara: las condiciones profundas permiten el núcleo y el núcleo crea la identidad de su celda o dominio. La celda visible es su huella, no un recinto que recibe una etiqueta previamente.

### 7.2. Propiedades conceptuales

| Propiedad | Qué controla |
| --- | --- |
| Intensidad | Magnitud de la transformación cerca del origen |
| Alcance | Distancia hasta la que sus efectos son reconocibles |
| Orientación | Direcciones preferentes de propagación |
| Transformación dominante | Gramática formal del dominio |
| Transformación secundaria | Variación y combinaciones internas |
| Actividad | Si está activo, estable, debilitado o apagado |
| Relación térmica | Cómo se vincula con las fuentes de lava |
| Relación con el sustrato | Qué materiales transforma y de qué manera |

Intensidad y alcance deben ser distintos. Un núcleo pequeño puede ser extremadamente extraño; uno enorme puede modificar suavemente un volumen mucho mayor.

### 7.3. Landmark central

El landmark expresa las mismas reglas que el dominio, llevadas a su máxima manifestación. Puede ser una cavidad, una masa suspendida, una arquitectura mineral o un conjunto de formas relacionadas. No necesita adoptar siempre la forma de un objeto central sólido.

Debe ser importante por escala, silueta y relación con el entorno. Su función como mazmorra, recurso, encuentro o lugar de construcción queda abierta; no todos los núcleos necesitan compartir una recompensa.

El núcleo ocupa el centro causal del dominio, que puede diferir de su centro geométrico. Un dominio muy alargado o desviado por una fractura puede tener una distribución asimétrica.

## 8. Dominios e interacción de influencias

### 8.1. Propagación

La influencia debe responder al entorno. Puede avanzar con facilidad por una fractura, expandirse verticalmente por una chimenea o encontrar resistencia en un macizo.

Cada dominio contiene variaciones: periferias, corredores de influencia intensa, zonas protegidas, cavidades secundarias y el entorno inmediato del núcleo. La distancia al centro no explica por sí sola todas estas diferencias.

### 8.2. Encuentros

Cuando dos dominios se encuentran, pueden producir:

- **Transición:** una influencia pierde peso mientras la otra aumenta.
- **Dominancia:** una transformación prevalece, conservando huellas de la anterior.
- **Interferencia:** ambas modifican las mismas formas y producen un paisaje combinado.
- **Barrera:** el encuentro favorece una separación material o un estrechamiento.
- **Concentración:** un paso canaliza varias influencias y se vuelve especialmente reconocible.

Estos comportamientos son posibilidades; el primer prototipo solo necesita unos pocos. Una frontera no debe cambiar simultáneamente todas las propiedades del mundo sin una causa visible.

### 8.3. Jerarquía de intensidad

Como regla general, el núcleo conserva la máxima expresión del surrealismo de su dominio. Las mezclas deben tener una intensidad controlada para que la superposición no convierta todas las fronteras en centros más espectaculares.

Una anomalía excepcional en una intersección puede existir, pero debe diseñarse y reconocerse como un acontecimiento importante, con una frecuencia acorde a ese papel.

## 9. Gramática del surrealismo y tono liminal

Las transformaciones siguientes son familias formales combinables, no biomas cerrados.

| Transformación | Expresión periférica | Expresión central |
| --- | --- | --- |
| Plegamiento | Estratos curvos, cornisas inclinadas | Superficies que se doblan y envuelven grandes vacíos |
| Repetición | Ritmos de pilares, arcos o huecos | Series monumentales con pequeñas anomalías |
| Suspensión | Fragmentos separados de paredes y techos | Conjuntos de masas suspendidas |
| Crecimiento | Vetas ramificadas y protuberancias | Arquitectura mineral u orgánica de gran escala |
| Vaciamiento | Huecos dentro de cuerpos reconocibles | Envolturas y cavidades anidadas |

Cada familia debe expresarse en varias escalas. El plegamiento puede aparecer en una pared, en una cámara y en el landmark, manteniendo una relación reconocible.

No es necesario que cada combinación tenga bloques o estructuras exclusivos. Dos reglas pueden actuar sobre las mismas formas básicas y producir diferencias suficientes.

El toque liminal procede de proporciones, continuidad, silencio visual y expectativas incompletas: una cámara demasiado regular, una terraza que parece conducir a algún lugar, una serie de arcos que desaparece en la niebla. Debe mantenerse vinculado a la materialidad del Nether.

Conviene reservar la abstracción extrema para lugares concretos. Las periferias más contenidas permiten que acercarse a un núcleo se sienta como un cambio real.

La extrañeza geométrica no implica cambiar la gravedad, crear portales espaciales o añadir otras mecánicas especiales. El primer desarrollo debe poder funcionar con el movimiento y las reglas habituales del juego.

## 10. Arquitectura de roca y vacío

El mundo necesita organizar las masas rocosas con el mismo cuidado que las cavidades. Una gran cantidad de huecos independientes produciría variedad local, pero poca geografía regional.

| Formación | Papel territorial | Relación con otras capas |
| --- | --- | --- |
| Cuenca cavernosa | Espacio regional reconocible, con terrazas y cavidades laterales | Puede alojar lava y recibir varias rutas |
| Macizo | Barrera extensa que obliga a elegir pasos | Conserva materiales y zonas poco transformadas |
| Cinturón de fractura | Dirección de continuidad entre regiones | Favorece conexiones e influencia alargada |
| Abismo | Separación, exposición y referencia visual | Puede mostrar niveles inferiores o recibir lava |
| Chimenea | Conexión entre alturas | Puede canalizar rutas, influencia y actividad |
| Envoltura hueca | Espacio interior separado del exterior | Expresa vaciamiento o crecimiento |
| Complejo magmático | Conjunto de cámaras, costras y conductos | Organiza la actividad térmica regional |

Las grandes cámaras deben tener estructura interna: terrazas, penínsulas de roca, pilares, cornisas o divisiones parciales. Un espacio monumental puede contener varios recorridos y escenas sin perder su identidad.

La proporción entre roca y vacío debe variar regionalmente. Algunos dominios son abiertos y permiten orientarse por vistas lejanas; otros son densos y se leen mediante materiales, formas y conexiones.

Es importante ofrecer espacios útiles para jugar: superficies donde construir, lugares donde detenerse y zonas desde las que observar. El terreno puede resultar hostil sin convertir cada desplazamiento en una labor continua de excavación o colocación de bloques.

## 11. Red de conexiones y autopistas

### 11.1. Función

Las autopistas son recorridos naturales de gran continuidad. Relacionan núcleos, atraviesan dominios y proporcionan una estructura de orientación a larga distancia. No tienen por qué parecer carreteras construidas.

Pueden adoptar la forma de galerías amplias, terrazas encadenadas, pasos entre macizos, cornisas de una cuenca o corredores dentro de una fractura.

### 11.2. Jerarquía

| Nivel | Función |
| --- | --- |
| Ruta principal | Relacionar grandes regiones y aproximaciones a núcleos |
| Ruta secundaria | Distribuir el acceso dentro de un dominio |
| Paso vertical | Relacionar redes situadas a distintas alturas |
| Atajo | Ofrecer una alternativa más directa, exigente o difícil de descubrir |
| Ramificación de exploración | Acceder a espacios opcionales y lugares apartados |

La red debe incluir bucles y alternativas. Un recorrido exclusivamente ramificado obliga a desandar caminos y reduce la capacidad de elegir. También puede haber bolsas aisladas que requieran excavar, siempre que no sean el patrón de acceso a todos los destinos importantes.

Los núcleos no necesitan conectarse todos directamente. Los pasos favorables, las barreras y los puntos de encuentro entre dominios pueden concentrar las rutas en cruces reconocibles.

### 11.3. Relación con el terreno

Una ruta importante tiene anchura, continuidad y entradas identificables. Puede atravesar tramos peligrosos, pero su función debe sobrevivir al detalle de superficie, la vegetación y las estructuras.

Las conexiones pueden dejar una huella propia: estratos expuestos, una sucesión de arcos, formas alineadas o vegetación característica. Seguir una autopista debe sentirse como recorrer una formación del mundo.

## 12. Verticalidad y chimeneas transitables

### 12.1. Organización vertical

La propuesta de partida combina tendencias verticales amplias con dominios tridimensionales. Las franjas pueden deformarse, inclinarse y quedar atravesadas por chimeneas. Arriba y abajo existen relaciones reconocibles sin convertir el mundo en pisos uniformes.

La profundidad puede influir en materiales, actividad y apertura, pero no debe explicar por sí sola todo el paisaje. Descender puede llevar de una zona activa a otra antigua y apagada.

Cubic Chunks permite plantear un mundo alto; el alcance vertical concreto es una decisión pendiente. La propuesta no exige una extensión infinita ni una dificultad que aumente indefinidamente al bajar.

### 12.2. Chimeneas

Una chimenea principal es una formación vertical con una ruta de ascenso y descenso integrada. Puede combinar rampas, cornisas en espiral, galerías interiores, terrazas y puentes.

El vacío central sirve como referencia visual; los recorridos pueden entrar y salir de sus paredes. Desde ciertos descansos deben verse accesos o destinos de otros niveles.

### 12.3. Condiciones de uso

- Las entradas principales deben estar relacionadas por un camino transitable con movimiento habitual.
- La subida debe requerir un esfuerzo proporcionado al desnivel, sin desvíos absurdos ni saltos obligatorios de precisión.
- Debe haber lugares donde detenerse, observar y preparar el siguiente tramo.
- Los cambios de dirección y las salidas deben poder reconocerse antes de alcanzarlos.
- La vegetación, los depósitos y la lava no deben bloquear accidentalmente el recorrido esencial.
- Una caída o un salto opcional no deben sustituir la conexión de vuelta cuando se pretende una ruta bidireccional.

No todas las grietas necesitan ser transitables. Debe poder distinguirse una chimenea que funciona como paso de un pozo que constituye un peligro.

La facilidad de desplazamiento puede variar entre dominios. Una región horizontal, una cuenca escalonada y un cruce vertical producen experiencias diferentes y dan valor a descubrir nuevas conexiones.

## 13. Lava: red, paisaje e historia

### 13.1. Funciones

La lava actúa como fuente de peligro, iluminación, referencia territorial, barrera y señal de actividad. Sus formas deben relacionarse con el sustrato, los núcleos y los espacios disponibles.

Se proponen cinco componentes:

1. **Fuentes y reservorios profundos:** origen conceptual de la actividad local.
2. **Conductos:** conexiones dentro de la roca o parcialmente expuestas.
3. **Cauces y cascadas:** tramos visibles que permiten leer dirección y desnivel.
4. **Lagos y mares regionales:** acumulaciones en cuencas con identidad propia.
5. **Huellas antiguas:** canales secos, costras, terrazas y niveles anteriores.

### 13.2. Coherencia de los niveles

Depósitos, salidas y barreras deben diseñarse como un conjunto. Un lago elevado necesita una geometría que lo contenga; si existe una salida inferior abierta, el paisaje debe explicar qué ha sucedido.

Los cauces visibles siguen la gravedad habitual. La extrañeza puede concentrarse en la forma del recipiente, en las masas que lo rodean o en su relación con un núcleo.

Un primer desarrollo puede resolver sistemas regionales de lava con límites claros. No necesita reconstruir una circulación global ni simular físicamente todos los procesos.

### 13.3. Relación con las rutas

La red de lava y la red de desplazamiento tienen funciones distintas. Pueden compartir un corredor, separarse ante una cascada y volver a encontrarse en otra cuenca.

Esa relación produce decisiones: seguir el borde de un mar, buscar un paso por encima de un cauce o ascender por una chimenea lateral mientras la lava cae por el centro.

No toda autopista debe estar acompañada de lava, ni toda fuente de lava debe conducir a un núcleo. La asociación debe ser útil sin convertirse en una equivalencia universal.

### 13.4. Actividad pasada

Los sistemas apagados permiten reconocer la historia: marcas de nivel compartidas por distintas paredes, costras rotas, antiguos cauces y vegetación que ocupa espacios antes activos.

Una reactivación puede cortar esas formas con fisuras recientes. Esa superposición debe ser legible y puede representarse de forma estática.

## 14. Materiales, superficies y detalle

Los materiales resultan del sustrato, la transformación dominante, la intensidad, la actividad y la relación con la lava. Su distribución debe cruzar y explicar formas, en lugar de limitarse a cubrir cada región con una paleta uniforme.

Las transiciones pueden dejar señales: una veta que se ramifica, una roca que adquiere cavidades, una costra térmica sobre otro material o una estructura antigua parcialmente transformada.

El detalle se organiza en tres escalas:

| Escala | Elementos | Condición de diseño |
| --- | --- | --- |
| Regional | Dominios, grandes fracturas y macizos | Mantener direcciones y contrastes reconocibles |
| Intermedia | Terrazas, pilares, arcos y cámaras laterales | Dar carácter al recorrido y relacionarlo con la región |
| Cercana | Costras, vetas, grietas y pequeños crecimientos | Expresar las mismas condiciones sin ocultar rutas y señales |

El detalle puede diferenciar suelo, pared y techo. Las formas deben responder a su soporte, exposición y contexto. Una decoración uniforme en todas las superficies borraría la arquitectura.

Cuando una forma pequeña entra en conflicto con una conexión importante, prevalece la función de la conexión. Cuando un detalle introduce una dirección visual, esta debería ser compatible con la estructura que pretende expresar.

## 15. Ecosistemas y paisajes emergentes

### 15.1. Condiciones anteriores al bioma

Los ecosistemas se determinan a partir de las condiciones ya generadas: materiales, actividad, influencia, cercanía a lava, apertura, soporte y posición dentro de la cavidad.

Pueden añadirse otras variables si producen diferencias útiles. Por ejemplo, una disponibilidad de nutrientes ficticia podría ayudar a explicar crecimientos orgánicos, pero no es un requisito inicial.

El nombre del bioma es una descripción del resultado. Un mismo ecosistema puede ocupar cámaras de arquitecturas distintas; una gran formación puede atravesar varios ecosistemas.

### 15.2. Ocupación del volumen

Una cámara puede contener ceniza en el fondo, organismos en terrazas, depósitos en paredes y crecimientos colgantes en el techo. Estas comunidades comparten contexto y desempeñan papeles distintos.

El techo de una región puede ser el suelo de otra, separado por una masa rocosa con espesor propio. Las raíces, vetas o perforaciones puntuales pueden revelar esa relación y anticipar un lugar que todavía no se ha visitado.

### 15.3. Ejemplos de resultados

Estos ejemplos son objetivos visuales posibles, no regiones que deban colocarse antes de generar sus causas.

| Paisaje | Condiciones que podrían producirlo |
| --- | --- |
| Catedrales basálticas | Gran apertura, repetición de soportes y material volcánico |
| Mares sepultados | Cuenca activa, costra parcialmente conservada y fracturas de exposición |
| Cuencas de ceniza | Espacio de acumulación, actividad próxima y superficies abiertas |
| Jardines invertidos | Crecimiento, soportes en el techo y condiciones favorables de actividad |
| Arrecifes infernales | Crecimiento ramificado alrededor de fuentes de energía |
| Osarios minerales | Restos o formas orgánicas integrados en transformaciones minerales |
| Dominios apagados | Actividad antigua, conductos secos y superficies colonizadas posteriormente |

Los restos orgánicos y los depósitos asociados son propuestas de contenido. No requieren todavía definir criaturas gigantes ni nuevos sistemas de juego.

## 16. Lectura ambiental y búsqueda de destinos

### 16.1. Compromiso central

El jugador debe poder obtener información útil mirando el terreno. La forma del mundo actúa como una herramienta de exploración.

Cada variable relevante debe tener al menos una manifestación visible. Las señales más importantes deben aparecer antes del destino y repetirse de forma consistente en diferentes lugares.

| Objetivo | Señales propuestas | Qué puede deducir el jugador |
| --- | --- | --- |
| Encontrar un núcleo | Mayor intensidad de transformación, formas orientadas y repetición creciente | Proximidad y dirección aproximada |
| Encontrar una subida | Terrazas escalonadas, aberturas superiores y continuidad de cornisas | Presencia de una conexión vertical utilizable |
| Encontrar una autopista | Galería continua, pasos alineados y motivos recurrentes | Posibilidad de viajar a otra región |
| Encontrar actividad de lava | Fisuras luminosas, costras recientes y exposición de conductos | Cercanía de un sistema activo |
| Encontrar un sistema apagado | Marcas de nivel, cauces secos y superficies envejecidas | Existencia de actividad anterior y espacios abandonados |
| Buscar un material | Exposiciones persistentes, vetas y formas asociadas al sustrato | Lugares con mayor probabilidad de encontrarlo |
| Reconocer una frontera | Mezcla o sustitución gradual de varias señales | Cambio de influencia dominante |

Estas asociaciones son una propuesta inicial; su fiabilidad deberá comprobarse al jugar. Un recurso específico necesita una relación ambiental definida antes de poder prometer su localización mediante señales.

### 16.2. Reglas para las señales

- Las señales principales deben distinguirse sin depender únicamente del color.
- Algunas deben poder verse desde lejos y otras servir para confirmar la interpretación al acercarse.
- La niebla y la iluminación deben conservar referencias suficientes para orientarse.
- Una dirección visual debe relacionarse con una estructura real, aunque no garantice un camino recto.
- Las zonas de mezcla pueden generar ambigüedad, pero deben ofrecer más de una pista.
- Las excepciones deben poder reconocerse y no invalidar constantemente lo aprendido.

Un núcleo puede permanecer oculto tras una pared. El jugador debería poder reconocer su influencia y buscar una conexión, sin que cada pista obligue a verlo directamente.

## 17. Estructuras, lugares especiales y oportunidades de juego

Las estructuras deben responder a la geografía. Un cruce de rutas, un estrechamiento sobre lava o una terraza protegida son ubicaciones con sentido para construcciones y encuentros.

La relación concreta con fortalezas, bastiones u otro contenido dependerá de la versión y del catálogo de mods. Este documento define funciones territoriales que después pueden alojar contenido compatible.

Conviene ofrecer distintos tipos de destino:

- **Centros:** núcleos que explican una región.
- **Pasos:** lugares que permiten atravesar una barrera o cambiar de altura.
- **Miradores:** posiciones desde las que comprender el entorno.
- **Refugios relativos:** superficies donde detenerse y construir, con peligros proporcionados.
- **Lugares apartados:** espacios opcionales con acceso menos evidente.

Las estructuras construidas deben ocupar una parte de los puntos de interés. La propia geografía debe proporcionar destinos memorables.

La hostilidad del Nether puede proceder de exposición, lava, criaturas y decisiones de ruta. Una navegación comprensible permite que el peligro sea interpretable y que el jugador prepare su desplazamiento.

## 18. Ejemplo completo de una región

### Condiciones iniciales

Una gran fractura atraviesa un sustrato volcánico antiguo. Hay potencial térmico elevado en profundidad y una tendencia espacial orientada hacia arriba.

### Núcleo y dominio

En esa estructura aparece un núcleo dominado por plegamiento y repetición. Su influencia se extiende especialmente por la fractura y alcanza varias alturas. El landmark es una gran cavidad rodeada de envolturas y arcos sucesivos.

### Arquitectura y conexiones

El dominio contiene una cuenca cavernosa, un macizo lateral y una chimenea que conecta dos rutas principales. Los estratos curvos reaparecen en las paredes, las cornisas y las aproximaciones al núcleo.

### Lava e historia

Un sistema antiguo dejó marcas horizontales y conductos secos. Una actividad más reciente abre fisuras y alimenta un lago inferior, contenido por parte de la costra anterior. Una cascada permite reconocer la conexión entre ambos niveles.

### Ecosistemas

Las terrazas antiguas soportan crecimientos. Algunas paredes conservan roca expuesta y el techo aloja organismos colgantes. Las superficies próximas a las fisuras activas presentan otras condiciones.

### Recorrido del jugador

El jugador entra por una galería seca y reconoce el antiguo nivel de lava. Desde un balcón observa arcos cada vez más juntos y deduce la dirección aproximada del núcleo. Para subir localiza cornisas continuas alrededor de la chimenea. Durante el ascenso ve el jardín del techo, al que accederá más adelante desde otra ruta.

La orientación, la historia y la identidad visual proceden de las mismas relaciones. Ninguna necesita colocarse como una escena independiente para que el conjunto tenga sentido.

## 19. Prioridades cuando los sistemas entran en conflicto

| Conflicto | Prioridad de diseño |
| --- | --- |
| Detalle frente a ruta principal | Conservar el recorrido útil y adaptar el detalle |
| Lago frente a galería inferior | Resolver contención y salida como un conjunto |
| Mezcla de influencias frente a identidad del núcleo | Preservar la jerarquía y limitar la saturación |
| Vegetación frente a orientación | Conservar vistas y señales necesarias en los puntos importantes |
| Monumentalidad frente a tránsito | Incorporar rutas y superficies útiles dentro de la gran forma |
| Estructura colocada frente a geografía | Adaptar su ubicación y acceso al lugar |
| Frontera regional frente a continuidad | Mantener conexiones y formas que deban atravesarla |

La continuidad debe mantenerse también al generar zonas en momentos distintos. Un trayecto, una superficie de lava o una forma grande debe conservar la misma identidad a ambos lados de cualquier división interna del generador.

## 20. Plan de prototipado

### Etapa 1. Comprobar la estructura regional

Construir una pequeña red con varios núcleos, dominios diferentes y una zona de encuentro. Usar pocos materiales y formas sencillas.

**Debe demostrar:** que los dominios comparten estructuras mayores, que sus diferencias se entienden y que las conexiones no parecen una serie de habitaciones independientes.

### Etapa 2. Resolver el desplazamiento

Añadir rutas principales, ramificaciones, un bucle y una chimenea con recorrido de ida y vuelta. Introducir grandes masas rocosas y obstáculos.

**Debe demostrar:** que se puede elegir camino, cambiar de altura y orientarse con el movimiento habitual, sin construir constantemente escaleras o puentes.

### Etapa 3. Incorporar la lava

Añadir una cuenca activa, un depósito a otra altura y un sistema apagado. Diseñar sus salidas y su relación con las rutas.

**Debe demostrar:** que los niveles y las conexiones son coherentes y que la lava aporta lectura territorial además de peligro.

### Etapa 4. Desarrollar la gramática visual

Expresar dos o tres transformaciones en escala regional, intermedia y cercana. Dar al núcleo una manifestación central reconocible y reservar zonas contenidas para el tono liminal.

**Debe demostrar:** que el jugador identifica una transformación lejos de su origen y reconoce su intensificación al aproximarse.

### Etapa 5. Añadir superficies y ecosistemas

Distribuir materiales, crecimientos y comunidades en suelo, paredes y techo. Incorporar algunos destinos secundarios.

**Debe demostrar:** que el contenido surge de las condiciones previas, refuerza la identidad y mantiene la navegación.

### Etapa 6. Ampliar variedad y extensión

Solo después de comprobar las relaciones anteriores, aumentar el catálogo, las escalas y la frecuencia de situaciones excepcionales. Revisar entonces los límites verticales y la adaptación del contenido existente.

El primer objetivo jugable puede ser una cuenca atravesada por una chimenea, con dos influencias próximas, un sistema de lava activo y otro abandonado. La red de varios núcleos se utiliza después para verificar que esas relaciones se mantienen más allá de una escena.

## 21. Criterios para evaluar el resultado

| Criterio | Pregunta de evaluación |
| --- | --- |
| Coherencia global | ¿Se reconoce una misma estructura al recorrer lugares distantes? |
| Relación entre capas | ¿La forma, el material, la lava y la vida cuentan una historia compatible? |
| Identidad de dominio | ¿Puede reconocerse la influencia sin haber visto su núcleo? |
| Jerarquía | ¿El centro tiene una presencia especial respecto a su periferia? |
| Interacción | ¿Las fronteras muestran consecuencias de ambos dominios? |
| Orientación | ¿El jugador puede justificar por qué elige una dirección? |
| Verticalidad | ¿Subir y volver es razonable y comprensible? |
| Elección de rutas | ¿Existen alternativas útiles y recorridos que vuelven a conectar? |
| Lava | ¿Sus depósitos y salidas resultan compatibles? |
| Escala cercana | ¿El detalle refuerza las grandes formas? |
| Surrealismo | ¿La extrañeza tiene una gramática que puede aprenderse? |
| Tono liminal | ¿Hay lugares inquietantes por su espacio, proporción y continuidad? |
| Variedad | ¿Las diferencias proceden de combinaciones de condiciones? |
| Habitabilidad | ¿Existen superficies útiles para observar, detenerse y construir? |

La evaluación debe hacerse recorriendo el mundo a pie y observándolo desde alturas distintas. Una imagen panorámica o un vuelo libre no bastan para valorar navegación y comprensión.

Una prueba especialmente útil consiste en pedir a alguien que busque una subida, un núcleo o un material concreto y que explique qué señales está siguiendo. Sus errores permiten distinguir entre falta de familiaridad y señales inconsistentes.

## 22. Decisiones pendientes

Estas decisiones deben concretarse mediante prototipos; no bloquean la definición conceptual:

1. Extensión vertical y tratamiento de sus extremos.
2. Escala y separación de núcleos, dominios y grandes rutas.
3. Cantidad inicial de variables y transformaciones.
4. Frecuencia de dominios apagados y superposición de historias.
5. Peso relativo de mares regionales y pequeños sistemas de lava.
6. Distancia y esfuerzo razonables entre conexiones verticales.
7. Recursos y oportunidades de juego asociados a cada condición ambiental.
8. Incorporación del contenido existente del Nether.
9. Intensidad de la niebla, iluminación y referencias visuales lejanas.
10. Rareza y función de las anomalías excepcionales.

Las decisiones sobre bloques, criaturas, recompensas y mecánicas especiales deben mantenerse separadas de la validación de la estructura del mundo. Pueden enriquecerla cuando su funcionamiento básico ya esté claro.

## 23. Referencia conceptual

La propuesta toma como punto de partida Viridium 2.0 y la conversación de diseño del 14 de noviembre de 2026. Describe un Nether propuesto, no una funcionalidad ya implementada en el repositorio.

- [Viridium, rama 2.0](https://github.com/pasteltogrunon/Viridium/tree/2.0): referencia del proyecto.
- [WorldGen](https://github.com/pasteltogrunon/Viridium/blob/2.0/src/main/java/net/junedev/viridium/worldgen/terrain/WorldGen.java): referencia para las capas relacionadas del Overworld.
- [CaveSystems](https://github.com/pasteltogrunon/Viridium/blob/2.0/src/main/java/net/junedev/viridium/worldgen/terrain/caves/CaveSystems.java): referencia existente de cámaras y conexiones planificadas.

La intención que debe conservar cualquier desarrollo posterior es esta: **las grandes estructuras explican el paisaje, y aprender a leer ese paisaje permite explorar mejor el mundo.**
