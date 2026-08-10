package procesos;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;

import model.EstadoProceso;
import model.PeticionServicio;
import model.RespuestaServicio;

enum TipoDifusion { COMPROMISO, COMISION };

public class Proceso extends Thread {

	private final static int UNDEFINED = 999_999_999;
	private final static int TIME_OUT = 10000;
	private final static TimeUnit TIME_UNIT = TimeUnit.MILLISECONDS;
	
	private final Object lockCompromiso = new Object();
	private final Object lockComision = new Object();
	
	private boolean hayCompromiso;
	private boolean hayComision;
	
	private int id;
	private AtomicInteger variable;
	private boolean error;
	private List<Integer> compromisos;
	private List<Integer> comisiones;
	
	private int numProcesos;
	private Semaphore sem;
	private Semaphore enPropuesta;
	private Object pantalla;
	private boolean debug;
	
	private Client cliente;
	private WebTarget servicio;
	
	public Proceso (int id, int numProcesos, String servicioUri, Semaphore sem, Object pantalla, boolean debug) {
		this.id = id;
		this.error = false;
		
		this.variable = new AtomicInteger(UNDEFINED);
		this.compromisos = Collections.synchronizedList(new ArrayList<>());
		this.comisiones = Collections.synchronizedList(new ArrayList<>());
		
		this.hayCompromiso = false;
		this.hayComision = false;
		
		this.sem = sem;
		this.enPropuesta = new Semaphore(0);
		
		this.numProcesos = numProcesos;
		
		if (pantalla != null) {
			this.pantalla = pantalla;
		} 
		else {
			this.pantalla = null;
		}
		
		this.debug = debug;
		
		this.cliente = ClientBuilder.newClient();
		URI uri = UriBuilder.fromUri(servicioUri).build();
		this.servicio = cliente.target(uri);
	}
	
	public long getId() {
		return id;
	}

	public EstadoProceso getEstado() {
		EstadoProceso estado = new EstadoProceso(this.id, this.variable.get(), this.error, this.compromisos, this.comisiones); 
		imprimePorPantalla("[DEBUG] --- Proceso (" + this.id + ") retorna su estado.\n");
		return estado;
	}
	
	public void changeError() {
		this.error = !this.error;
		imprimePorPantalla("[DEBUG] --- Proceso (" + this.id + ") cambia su estado de error a " + this.error + "\n");
	}
	
	public void propuesta(int v) {
		imprimePorPantalla("[DEBUG] --- Proceso (" + this.id + ") ha recibido mensaje de propuesta.\n");
		
		this.variable.set(UNDEFINED);
		this.compromisos.clear();
		this.comisiones.clear();
		
		this.hayCompromiso = false;
		this.hayComision = false;
				
		// INDICAR AL RESTO DE PROCESOS QUE ESTE YA HA RECIBIDO EL MENSAJE DE PROPUESTA
		this.enPropuesta.release(numProcesos - 1);

		// MULTIDIFUNDIR MENSAJE DE COMPROMISO DE ESTE PROCESO
		this.compromisos.add(v);
		multidifundir(TipoDifusion.COMPROMISO, v);
	}
	
	public void compromiso(int v) {
		imprimePorPantalla("[DEBUG] --- Proceso (" + this.id + ") ha recibido mensaje de compromiso.\n");
		
		// ESPERAR A QUE ESTE PROCESO HAYA RECIBIDO EL MENSAJES DE PROPUESTA
		try {
			this.enPropuesta.tryAcquire(TIME_OUT, TIME_UNIT);
		} 
		catch(InterruptedException e) {
			imprimePorPantalla("[DEBUG] --- Proceso (" + this.id + ") interrumpido esperando a que le llegue propuesta.\n");
			return ;
		}
		
		synchronized(lockCompromiso) {
			// REGISTRAR MENSAJE DE COMPROMISO
			this.compromisos.add(v);
			
			if (!hayCompromiso) {
				Map<Integer, Integer> quorumCompromisos = new HashMap<>();
				
				for (Integer compromiso: compromisos) {
					quorumCompromisos.put(compromiso, quorumCompromisos.getOrDefault(compromiso, 0) + 1);
				}
				
				for (Map.Entry<Integer, Integer> entry: quorumCompromisos.entrySet()) {
					int compromiso = entry.getKey();
					int numCompromisos = entry.getValue();
					
					if (numCompromisos >= (numProcesos / 2 + 1)) {
						this.hayCompromiso = true;
						
						// MODIFICAR EL VALOR DE LA VARIABLE SI HAY UNA MAYORIA DE COMPROMISOS
						this.variable.set(compromiso);
						
						// MULTIDIFUNDIR MENSAJE DE COMISION DE ESTE PROCESO
						this.comisiones.add(compromiso);
						multidifundir(TipoDifusion.COMISION, compromiso);
						
						// VERIFICAR QUORUM DE COMISIONES POR SI YA LLEGARON LOS MENSAJES DEL RESTO DE PROCESOS
					    synchronized(lockComision) {
					        if (!hayComision) {
					            Map<Integer, Integer> quorumComisiones = new HashMap<>();
					            
					            for (Integer c : this.comisiones) {
					                quorumComisiones.put(c, quorumComisiones.getOrDefault(c, 0) + 1);
					            }
					            
					            for (Map.Entry<Integer, Integer> e : quorumComisiones.entrySet()) {
					            	int comision = e.getKey();
					            	int numComisiones = e.getValue();
					            	
					            	// COMPROBAR QUE HAY UNA MAYORIA DE COMISIONES PARA EL VALOR COMETIDO EN LA VARIABLE
					                if (comision == this.variable.get() && numComisiones >= (numProcesos / 2 + 1)) {
					                    hayComision = true;
					                    
					                    // MANDAR MENSAJE DE CONFIRMACION
					                    confirmacion();
					                    break;
					                }
					                
					                // SI NO SE RECIBE UNA MAYORIA DE COMISIONES PARA EL VALOR COMETIDO REFRESCAR VARIABLE
					                if (comision != this.variable.get() && numComisiones >= (numProcesos / 2 + 1)) {
					                    hayComision = false;
					                    this.variable.set(UNDEFINED);
					                    break;
					                }
					            }
					        }
					    }
					    
						break;
					}
				}	
			}
		}
	}
	
	public void comision(int v) {
		imprimePorPantalla("[DEBUG] --- Proceso (" + this.id + ") ha recibido mensaje de comision.\n");
		
		synchronized(lockComision) {
			// REGISTRAR MENSAJE DE COMISION
			this.comisiones.add(v);
			
			if (!hayComision) {
				Map<Integer, Integer> quorum = new HashMap<>();
					
				for (Integer comision: this.comisiones) {
					quorum.put(comision, quorum.getOrDefault(comision, 0) + 1);
				}
						
				for (Map.Entry<Integer, Integer> entry: quorum.entrySet()) {
					int comision = entry.getKey();
					int numComisiones = entry.getValue();
						
					// COMPROBAR QUE HAY UNA MAYORIA DE COMISIONES PARA EL VALOR COMETIDO EN LA VARIABLE
		            if (comision == this.variable.get() && numComisiones >= (numProcesos / 2 + 1)) {
		            	hayComision = true;
		            	
		                // MANDAR MENSAJE DE CONFIRMACION
		                confirmacion();
		                break;
		            }
		            
		            // SI NO SE RECIBE UNA MAYORIA DE COMISIONES PARA EL VALOR COMETIDO REFRESCAR VARIABLE
		            if (comision != this.variable.get() && numComisiones >= (numProcesos / 2 + 1)) {
	                    hayComision = false;
	                    this.variable.set(UNDEFINED);
	                    break;
	                }
				}
			}	
		}
	}
	
	private void confirmacion() {
		PeticionServicio peticion = new PeticionServicio(UNDEFINED, this.id);
		
		imprimePorPantalla("[DEBUG] --- Proceso (" + this.id + ") manda mensaje de confirmacion.\n");
		
		RespuestaServicio respuesta = this.servicio.path("confirmacion")
				.request(MediaType.APPLICATION_JSON)
				.post(Entity.entity(peticion, MediaType.APPLICATION_JSON), RespuestaServicio.class);
		
		imprimePorPantalla("[DEBUG] --- Servicio (" + servicio.getUri() + ") responde: " + respuesta.message + "\n");
	}
	
	@Override
	public void run() {
		// ESPERAR A SER LIBERADO
		try {
			imprimePorPantalla("[DEBUG] --- Proceso (" + this.id + ") iniciado exitosamente.\n");
			this.sem.acquire();
		} 
		catch(InterruptedException e) {
			imprimePorPantalla("[DEBUG] --- Proceso (" + this.id + ") interrumpido esperando para ser liberado." +
							   "[DEBUG] --- " + e.getCause() + ": " + e.getMessage());
		}
		
		// CERRAR CLIENTE HTTP
		this.cliente.close();
		
		// LIBERAR RECURSOS
		this.compromisos.clear();
		this.comisiones.clear();
		
		imprimePorPantalla("[DEBUG] --- Proceso (" + this.id + ") finalizado.\n");
	}
	
	private void multidifundir(TipoDifusion tipo, int v) {
		
		boolean salir = false;
		RespuestaServicio respuesta;
		
		for (int i = 0; i < this.numProcesos && !salir; i++) {
			if (i != this.id) {
				switch(tipo) {
					case COMPROMISO:
						if (!this.error) {
							imprimePorPantalla("[DEBUG] --- Proceso (" + this.id + ") manda mensaje de compromiso al proceso (" + i + ").\n");
							
							PeticionServicio peticion = new PeticionServicio(v, i);
							respuesta = this.servicio.path("compromiso")
									.request(MediaType.APPLICATION_JSON)
									.post(Entity.entity(peticion, MediaType.APPLICATION_JSON), RespuestaServicio.class);
						}
						else {
							int random = (int) (Math.random() * 100);
							
							imprimePorPantalla("[DEBUG] --- Proceso (" + this.id + ") manda mensaje de compromiso al proceso (" + i + ").\n");
							
							PeticionServicio peticion = new PeticionServicio(random, i);
							respuesta = this.servicio.path("compromiso")
									.request(MediaType.APPLICATION_JSON)
									.post(Entity.entity(peticion, MediaType.APPLICATION_JSON), RespuestaServicio.class);
						}
						
						if (respuesta.statusCode == 200) {
							imprimePorPantalla("[DEBUG] --- Servicio (" + servicio.getUri() + ") responde: " + respuesta.message + "\n");	
						}
						else {
							imprimePorPantalla("[DEBUG] --- Servicio (" + servicio.getUri() + ") responde: " + respuesta.message + "\n");	
						}
						
						break;
						
					case COMISION:
						if (!this.error) {
							imprimePorPantalla("[DEBUG] --- Proceso (" + this.id + ") manda mensaje de comision al proceso (" + i + ").\n");
							
							PeticionServicio peticion = new PeticionServicio(v, i);
							respuesta = this.servicio.path("comision")
								.request(MediaType.APPLICATION_JSON)
								.post(Entity.entity(peticion, MediaType.APPLICATION_JSON), RespuestaServicio.class);	
						}
						else {
							int random = (int) (Math.random() * 100);
							
							imprimePorPantalla("[DEBUG] --- Proceso (" + this.id + ") manda mensaje de comision al proceso (" + i + ").\n");
							
							PeticionServicio peticion = new PeticionServicio(random, i);
							respuesta = this.servicio.path("comision")
									.request(MediaType.APPLICATION_JSON)
									.post(Entity.entity(peticion, MediaType.APPLICATION_JSON), RespuestaServicio.class);
						}
				
						if (respuesta.statusCode == 200) {
							imprimePorPantalla("[DEBUG] --- Servicio (" + servicio.getUri() + ") responde: " + respuesta.message + "\n");	
						}
						else {
							imprimePorPantalla("[DEBUG] --- Servicio (" + servicio.getUri() + ") responde: " + respuesta.message + "\n");	
						}
						
						break;
						
					default: 
						salir = !salir;
						break;
				}
			}
		}
	}
	
	private void imprimePorPantalla(String msg) {
		if (debug) {
			synchronized(this.pantalla) {
				System.out.print(msg);
				System.out.flush();
			}
		}
	}
}