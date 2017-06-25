# Implementación live del programa
El programa ahora funciona con backtesting. Pero necesita funcionar en live.
Ejecutar el programa con los parámetros **[pair] [mode]** donde:

- **pair**: Par de monedas
- **mode**: "backtest:<days>" o "live"

En modo **backtest:<days>** se comporta de igual modo que lo hace ahora.
En modo **live**, es más complejo:
En primera instancia, se comporta igual que el modo actual, con la diferencia de que:
- En vez de cambiar la variable "balance", ejecuta una compra real.
- En vez de ejecutar un loop con los datos historicos, duerme (candleSize) y le hace un fetch a los datos
  de la API.
  
Se puede implementar ya con facilidad, usando interfaces:

```kotlin
interface Exchange {
    val moneyBalance: Double
    val coinBalance: Double
    fun fetch()
    fun buy()
    fun sell()
}

class PoloniexBacktestExchange : Exchange // ...
class PoloniexLiveExchange : Exchange // ...
```

## Problema del estado
Cuando el programa se cierra, debería tener información de lo que hizo antes. Porque, primero, necesita los datos
históricos para saber cómo actuar. Y segundo, necesita la información del precio de las últimas ventas y compras
que hizo.
Entonces, los datos históricos los puede obtener desde el gráfico (aunque hay perdida de presición: ¿en qué candle?), y
los datos de monedas los puede obtener de un archivo json.
Pero también debe tener el historial de todos los cambios que yo hize antes. Entonces, necesitaría recuperar un "log"
de las compras y ventas. Bien: Los datos de compras y ventas ya los debería tener. Necesitaría un "epoch" cada evento
y estaría listo.
Se puede pensar como "Event". SellEvent y BuyEvent. Además, uno elimina la lógica de las variables "consecutiveSell"
y "consecutiveBuys" que forman parte del estado y no se pueden obtener independientemente (como si se pueden los MA, MACD, etc.)
* Un log también es necesario para ver cuándo el programa se prende, apaga, encuentra, etc.
