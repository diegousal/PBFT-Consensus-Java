package servicios;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;

import model.ConfiguracionArranque;
import model.EstadoProceso;
import model.PeticionServicio;
import model.RespuestaServicio;

import procesos.Proceso;

@Path("servicio")
@Singleton
public class Servicio {

	private final boolean DEBUG = false;
	
	private final int TIME_OUT = 5000;
	private final TimeUnit TIME_UNIT = TimeUnit.MILLISECONDS;
	private final String SERVICIO_URI = "http://localhost:8080/servidorPBFT/api/servicio";
	private final Object PANTALLA = new Object();
	
	private Semaphore sem;
	private Client cliente;
	private Map<Integer, Proceso> procesos;
	private Map<Integer, WebTarget> servicios;
	private ConcurrentMap<Integer, Semaphore> clientes;
	
	private boolean yaIniciado = false;
	private boolean yaTerminado = false;
	
	@POST
	@Path("iniciar")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public RespuestaServicio inicio(ConfiguracionArranque config) {
		if (!yaIniciado) {
			// INICIAR VARIABLES DE CONTROL
			yaIniciado = true;
			yaTerminado = false;
			
			// CREAR ESTRUCTURAS DE DATOS
			sem = new Semaphore(0);
			cliente = ClientBuilder.newClient();
			procesos = new HashMap<>();
			servicios = new HashMap<>();
			clientes = new ConcurrentHashMap<>();
			
			// OBTENER CONFIGURACION DE LOS PROCESOS
			int primerId = config.primerId;
			int numProcesos = config.numProcesos;
			int numProcesosTotales = config.numProcesosTotales;
			Map<String, String> procesosConfig = config.procesos;
			
			// MAPEAR TODOS LOS PROCESOS CON SUS SERVICIOS
			for (Map.Entry<String, String> procesoConfig : procesosConfig.entrySet()) {
				int id = Integer.parseInt(procesoConfig.getKey());
				String servicioUri = procesoConfig.getValue();
				
				URI uri = UriBuilder.fromUri(servicioUri).build();
				WebTarget servicio = cliente.target(uri);
				
				servicios.put(id, servicio);
			}
			
			// CREAR LOS PROCESOS		
			for(int i = 0; i < numProcesos; i++) {				
				Proceso proceso = new Proceso(primerId + i, numProcesosTotales, SERVICIO_URI, sem, PANTALLA, DEBUG);
				procesos.put(primerId + i, proceso);
				proceso.start();
			}
			
			// MOSTRAR ESTRUCTURA DE DATOS POR PANTALLA
			StringBuilder msg = new StringBuilder();
				
			msg.append("\n[DEBUG] --- Mostrando listado de procesos:\n\n");
			for (Map.Entry<Integer, WebTarget> servicio : servicios.entrySet()) {
				msg.append("[DEBUG] --- Proceso -> " + servicio.getKey() + " - " + servicio.getValue().getUri() + "\n");
			}
			msg.append("\n");
				
			imprimePorPantalla(msg.toString());
		}
		
		return new RespuestaServicio(200, "Servicio iniciado con exito, esperando peticiones...");
	}
	
	@GET
	@Path("apagar")
	@Produces(MediaType.APPLICATION_JSON)
	public RespuestaServicio apagar() {
	    if (yaIniciado && !yaTerminado) {
	        yaTerminado = true;

	        // EJECUTAR EL APAGADO EN SEGUNDA PLANO
	        new Thread(() -> {
	        	// INDICAR A LOS PROCESOS QUE PUEDEN FINALIZAR
	            sem.release(procesos.size());
	            
	            // ESPERAR A QUE TODOS LOS PROCESOS HAYAN FINALIZADO
	            for (Proceso p : procesos.values()) {
	                try {
	                    p.join();
	                } catch (InterruptedException e) { 
	                	imprimePorPantalla("[DEBUG] --- Servicio interrumpido esperando por finalizaci�n del proceso (" + p.getId() + ").\n");
	                }
	            }

	            // CERRAR EL CLIENTE HTTP
	            cliente.close();
	            
	            // LIMPIAR ESTRUCTURAS DE DATOS
	            servicios.clear();
	            procesos.clear();
	            clientes.clear();
	            
	            // DAR UN MARGEN DE SEGURIDAD PARA QUE LA RESPUSTA SE HAYA ENVIADO
	            try { 
	            	Thread.sleep(10000); 
	            } catch (InterruptedException e) {
                	imprimePorPantalla("[DEBUG] --- Servicio interrumpido esperando por time out de finalizacion.\n");
	            }

	            // FINALIZAR SERVICIO
	            System.exit(0);
	        }).start();

	        return new RespuestaServicio(200, "Cierre del servicio inicado.");
	    }

	    return new RespuestaServicio(400, "El servicio no puede iniciar el apagado.");
	}
	
	@POST
	@Path("fallo")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public RespuestaServicio fallo(PeticionServicio peticion) {
		Proceso p = procesos.get(peticion.id);
		
		if (p == null) {
			return new RespuestaServicio(400, "Proceso (" + peticion.id + ") no esta registrado en este servicio.");
		}
		else {
			p.changeError();
			return new RespuestaServicio(200, "Proceso (" + p.getId() + ") ha cambiado su estado de error con exito.");
		}
	}
	
	@POST
	@Path("estado")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public EstadoProceso estado(PeticionServicio peticion) {
		Proceso p = procesos.get(peticion.id);
		
		if (p == null) {
			return new EstadoProceso(400, "Proceso (" + peticion.id + ") no esta registrado en este servicio.");
		}
		else {
			EstadoProceso estado =  p.getEstado();
			estado.statusCode = 200;
			estado.message = "Proceso (" + p.getId() + ") retorna su estado.";
			return estado;
		}
	}
	
	@POST
	@Path("propuesta")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public RespuestaServicio propuesta(PeticionServicio peticion) {
		int v = peticion.v;
		int id = peticion.id;
		
		Proceso p = procesos.get(id);
		
		if (p == null) {
			return new RespuestaServicio(400, "El proceso (" + id + ") no esta registrado en este servicio.");
		}
		else {
			Semaphore sem = new Semaphore(0);
			clientes.put(id, sem);
			
			// MANDAR MENSAJE DE PROPUESTO AL PROCESO
			p.propuesta(v);
			
			try {
				boolean result = sem.tryAcquire(TIME_OUT, TIME_UNIT);
				
				// LIMPIAR PETICION DE ESPERA
				clientes.remove(id);
				
				if (!result) {
					return new RespuestaServicio(400, "No se ha recibido mensaje de confirmacion del proceso (" + id + ").");
				}
				else {
					return new RespuestaServicio(200, "Proceso (" + id + ") envia su mensaje de confirmacion.");	
				}
			} catch(InterruptedException e) {
				return new RespuestaServicio(400, "Se ha interrumpido al servicio esperando por el mensaje de confirmacion del proceso (" + id + ").");
			}
		}
	}
	
	@POST
	@Path("compromiso")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public RespuestaServicio compromiso(PeticionServicio peticion) {
		int v = peticion.v;
		int id = peticion.id;
			
		Proceso p = procesos.get(id);
			
		if (p != null) {
			p.compromiso(v);
			return new RespuestaServicio(200, "Compromiso enviado al proceso (" + id + ") en este servicio.");
		}
		else {
			WebTarget servicio = servicios.get(id);
			RespuestaServicio respuesta = servicio.path("compromiso")
				.request(MediaType.APPLICATION_JSON)
				.post(Entity.entity(peticion, MediaType.APPLICATION_JSON), RespuestaServicio.class);
			
			if (respuesta.statusCode == 200) {
				return new RespuestaServicio(200, "Compromiso enviado al servicio (" + servicio.getUri() + ") para el proceso (" + id + ").");
			}
			else {
				return new RespuestaServicio(400, "Compromiso no enviado al servicio (" + servicio.getUri() + ") para el proceso (" + id + ").");
			}
		}
	}
	
	@POST
	@Path("comision")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public RespuestaServicio comision(PeticionServicio peticion) {
		int v = peticion.v;
		int id = peticion.id;
			
		Proceso p = procesos.get(id);
		
		if (p != null) {
			p.comision(v);
			return new RespuestaServicio(200, "Comision enviado al proceso (" + id + ") en este servicio.");
		}
		else {
			WebTarget servicio = servicios.get(id);
			RespuestaServicio respuesta = servicio.path("comision")
				.request(MediaType.APPLICATION_JSON)
				.post(Entity.entity(peticion, MediaType.APPLICATION_JSON), RespuestaServicio.class);
			
			if (respuesta.statusCode == 200) {
				return new RespuestaServicio(200, "Comision enviado al servicio (" + servicio.getUri() + ") para el proceso (" + id + ").");
			}
			else {
				return new RespuestaServicio(400, "Comision no enviado al servicio (" + servicio.getUri() + ") para el proceso (" + id + ").");
			}
		}
	}
	
	@POST
	@Path("confirmacion")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public RespuestaServicio confirmacion(PeticionServicio peticion) {
		// LIBERAR AL CLIENTE, SIMULAR MENSAJE DE CONFIRMACION
		Semaphore sem = clientes.get(peticion.id);
		sem.release();
		
		return new RespuestaServicio(200, "Confirmacion enviada al cliente.");
	}
	
	private void imprimePorPantalla(String msg) {
		if (DEBUG) {
			synchronized(this.PANTALLA) {
				System.out.print(msg);
				System.out.flush();
			}
		}
	}
}