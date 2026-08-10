package model;

import java.util.Map;

public class ConfiguracionArranque {

	public int primerId;
	public int numProcesos;
	public int numProcesosTotales;
	public Map<String, String> procesos;
	
	public ConfiguracionArranque() {}

	public ConfiguracionArranque(int primerId, int numProcesos, int numProcesosTotales, Map<String, String> procesos) {
		this.primerId = primerId;
		this.numProcesos = numProcesos;
		this.numProcesosTotales = numProcesosTotales;
		this.procesos = procesos;
	}
}
