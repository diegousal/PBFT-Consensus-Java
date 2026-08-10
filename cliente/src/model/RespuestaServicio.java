package model;

public class RespuestaServicio {

	public int statusCode;
	public String message;
	
	public RespuestaServicio() {}
	
	public RespuestaServicio(int statusCode, String message) {
		this.statusCode = statusCode;
		this.message = message;
	}
}
