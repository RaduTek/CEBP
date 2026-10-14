# The Game of Life

Simulate a population of living cells, each with the goal of feeding and reproducing.

There is a limited number of food units (resources) that cells consume. A food unit sustains a cell for a given time T_full, after which the cell gets hungry. If it doesn't eat within another specified time T_starve, the cell dies, resulting in a random number of food units between 1 and 5.

After eating at least 10 times, a cell will multiply before getting hungry again. There are two types of cells: sexual and asexual. Asexual cells multiply through division, resulting in two hungry cells.

Sexual cells only multiply if they encounter another cell looking to reproduce, resulting in a third cell that was initially hungry.

In the simulation, cells will be represented by distinct threads of execution.
