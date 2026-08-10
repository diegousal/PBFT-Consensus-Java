package model;

import java.util.List;

public class EstadoProceso {

	public int statusCode;
	public String message;
	public int id;
	public int variable;
	public boolean error;
	public List<Integer> compromisos;
	public List<Integer> comisiones;
	
	public EstadoProceso() {}
	
	public EstadoProceso(int statusCode, String message) {
		this.statusCode = statusCode;
		this.message = message;
	}
	
	public EstadoProceso(int id, int variable, boolean error, List<Integer> compromisos, List<Integer> comisiones) {
		this.id = id;
		this.variable = variable;
		this.error = error;
		this.compromisos = compromisos;
		this.comisiones = comisiones;
	}
}
