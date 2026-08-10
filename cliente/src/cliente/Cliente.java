package cliente;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.InvocationCallback;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;

import model.ConfiguracionArranque;
import model.EstadoProceso;
import model.PeticionServicio;
import model.RespuestaServicio;

public class Cliente {

	private final static boolean DEBUG = false;
	
	private final static int UNDEFINED = 999_999_999;
	private final static int TIME_OUT = 7000;
	private final static TimeUnit TIME_UNIT = TimeUnit.MILLISECONDS;
	private final static int DEFAULT_PROCESOS = 12;
	private final static String DEFAULT_SERVICIO = "localhost:8080";
	
	private final static Object PANTALLA = new Object();
	
	private static Scanner scanner;
	private static Client cliente;
	private static List<WebTarget> servicios;
	private static Map<Integer, WebTarget> procesos;
	
	public static void main(String[] args) {
		// INICIALIZAR ESTRUCTURAS DE CONTROL
		scanner = new Scanner(System.in);
		cliente = ClientBuilder.newClient();
		servicios = new ArrayList<WebTarget>();
		procesos = new HashMap<Integer, WebTarget>();
		
		// OBTENER INFORMACION DE CONFIGURACION DE LOS SERVICIOS
		Map<String, Integer> nodos = obtenerNodos(args);
		
		// ARRANCAR SERVICIOS
		iniciarServicios(nodos);
		
		while (true) {
			imprimePorPantalla("> ");
            String input = scanner.nextLine().trim();
            
            if (input.isEmpty()) {
            	continue;
            }

            // MOSTRAR AYUDA
            if (input.equalsIgnoreCase("h")) {
                mostrarAyuda();
                continue;
            }
           
            // MOSTRAR ESTADO
            if (input.equalsIgnoreCase("e")) {
                mostrarEstado();
                continue;
            }

            // CAMBIAR ESTADO DE ERROR DE UN PROCESO
            if (input.matches("^f\\d+$")) {
            	try {
            		int id = Integer.parseInt(input.substring(1, input.length()));
                    modificarEstadoError(id);
            	} catch (NumberFormatException e) {
            		imprimePorPantalla("Sintaxis de comando erronea, se debe especificar un numero.\n");
            		if (DEBUG) {
            			imprimePorPantalla("[DEBUG] --- " + e.getCause() + ": " + e.getMessage() + "\n");
            		}
            	}
            	
            	continue;
            }

            // INICIAR CONSENSO PARA UN DETERMINADO VALOR
            if (input.matches("^s\\d+$")) {
            	try {
            		int valor = Integer.parseInt(input.substring(1, input.length()));
            		iniciarConsenso(valor);
            	} catch (NumberFormatException e) {
            		imprimePorPantalla("Sintaxis de comando erronea, se debe especificar un numero.\n");
            		if (DEBUG) {
            			imprimePorPantalla("[DEBUG] --- " + e.getCause() + ": " + e.getMessage() + "\n");
            		}
            	}
            	
                continue;
            }
            
            // CERRAR SERVICIOS
            if (input.equalsIgnoreCase("q")) {
            	cerrarServicios();
            	scanner.close();
            	break;
            }
            
            imprimePorPantalla("Comando no reconocido. Pulsa 'h' para ayuda.\n");
        }
	}
	
	private static Map<String, Integer> obtenerNodos(String[] args) {
		Map<String, Integer> nodos = new HashMap<>();
		
		if (args.length != 2 || !args[0].equals("--nodos")) {
			nodos.put(DEFAULT_SERVICIO, DEFAULT_PROCESOS);
			return nodos;
		}
		
		String[] nodosArray = args[1].split(",");

		for (String nodo : nodosArray) {
			String[] config = nodo.split(":");

			if (config.length != 3) {
				throw new IllegalArgumentException("Formato incorrecto: " + nodo + " (debe ser ip:puerto:num_procesos).");
			}

			try {
				int numProcesos = Integer.parseInt(config[2]);
				String dominio = config[0] + ":" + config[1];
				nodos.put(dominio, numProcesos);
			} catch(NumberFormatException e) {
				throw new IllegalArgumentException("Formato incorrecto: " + nodo + " (num_procesos debe ser el numero de procesos que gestiona el servicio).");
			}
		}
		
		return nodos;
	}
	
	private static void iniciarServicios(Map<String, Integer> nodos) {
		int offset = 0, j = 0;
		int numProcesosTotales = 0;
		Map<String, String> procesosConfig = new HashMap<>();
		
		for (Map.Entry<String, Integer> nodo : nodos.entrySet()) {
			String dominio = nodo.getKey();
			int numProcesos = nodo.getValue();
			
			URI uri = UriBuilder.fromUri(String.format("http://%s/servidorPBFT/api/servicio", dominio)).build();
			WebTarget servicio = cliente.target(uri);
			
			servicios.add(servicio);
			
			for(int i = 0; i < numProcesos; i++) {
				procesos.put(i + offset, servicio);
				procesosConfig.put(String.format("%d", i + offset), uri.toString());
			}
			
			offset += numProcesos;
			numProcesosTotales += numProcesos;
		}
		
		offset = 0;
		for (Map.Entry<String, Integer> nodo : nodos.entrySet()) {
			int numProcesos = nodo.getValue();
			WebTarget servicio = servicios.get(j);
			
			ConfiguracionArranque bootConfig = new ConfiguracionArranque(offset, numProcesos, numProcesosTotales, procesosConfig);
			
			RespuestaServicio respuesta = servicio.path("iniciar")
					.request(MediaType.APPLICATION_JSON)
					.post(Entity.entity(bootConfig, MediaType.APPLICATION_JSON), RespuestaServicio.class);
			
			if (DEBUG) {
				imprimePorPantalla("[DEBUG] --- Servicio (" + servicio.getUri() + ") inicializado con exito.\n" +
								   "[DEBUG] --- Servicio (" + servicio.getUri() + ") responde: " + respuesta.message + "\n");
			}
			
			offset += numProcesos;
			j++;
		}
		
		if (DEBUG) {
			imprimePorPantalla("\n[DEBUG] --- Mostrando lista de servicios:\n\n");
			for (WebTarget servicio: servicios) {
				imprimePorPantalla("[DEBUG] --- Servicio - " + servicio.getUri() + "\n");
			}
			
			imprimePorPantalla("\n[DEBUG] --- Mostrando lista de procesos:\n\n");
			for (Map.Entry<Integer, WebTarget> proceso : procesos.entrySet()) {
				imprimePorPantalla("[DEBUG] --- Proceso -> " + proceso.getKey() + " - " + proceso.getValue().getUri() + "\n");
			}
		}
	}
	
	@SuppressWarnings("unused")
	public static void cerrarServicios() {
		CountDownLatch latch = new CountDownLatch(servicios.size());
		
		// OBTENER UN LISTADO QUE INCLUYA CADA SERVICIO UNA SOLA VEZ
		for (WebTarget servicio: servicios) {
			servicio.path("apagar").request().async().get(new InvocationCallback<RespuestaServicio>(){
				
				@Override
				public void completed(RespuestaServicio respuesta) {
					if (DEBUG) {
						if (respuesta.statusCode == 200) {
							imprimePorPantalla("[DEBUG] --- Servicio (" + servicio.getUri() + ") inicia finalizacion con exito.\n" +
									   		   "[DEBUG] --- Servicio (" + servicio.getUri() + ") responde: " + respuesta.message + "\n");
						}
						else {
							imprimePorPantalla("[DEBUG] --- Servicio (" + servicio.getUri() + ") no ha iniciado finalizacion.\n" +
										   	   "[DEBUG] --- Servicio (" + servicio.getUri() + ") responde: " + respuesta.message + "\n");
						}
					}
					// REDUCIR CONTADOR
					latch.countDown();
				}
				
				@Override
				public void failed(Throwable throwable) {
					if (DEBUG) {
						imprimePorPantalla("[DEBUG] --- Servicio (" + servicio.getUri() + ") ha lanzado una excepción mientras finalizaba.\n" +
										   "[DEBUG] --- " + throwable.getCause() + ": " + throwable.getMessage() + "\n");
					}
					// REDUCIR CONTADOR
					latch.countDown();
				}
				
			});
		}
		
		// ESPERAR A QUE TODOS LOS SERVICIOS CONFIRMEN QUE INICIAN SU CIERRE
		try {
			boolean result = latch.await(TIME_OUT, TIME_UNIT);
			
			if (!result && DEBUG) {
				imprimePorPantalla("[DEBUG] -System.out.println(\"Número de compromisos: \"+ (procesos.size() / 2 + 1));-- Cliente interrumpido por time out esperando por el cierre de todos los servicios.\n");
			}
		} catch (InterruptedException e) {
			if (DEBUG) {
				imprimePorPantalla("[DEBUG] --- Cliente interrumpido por excepcion esperando por el cierre de todos los servicios.\n" +
								   "[DEBUG] --- " + e.getCause() + ": " + e.getMessage() + "\n");
			}
		}
		
		// CERRAR TODOS LOS CLIENTES HTTP
		cliente.close();
		
		// LIMPIAR TODAS LAS ESTRUCTURAS DE DATOS
		servicios.clear();
		procesos.clear();
	}
	
	public static void modificarEstadoError(int id) {
		WebTarget servicio = procesos.get(id);
		
		if (servicio == null) {
			imprimePorPantalla("No existe ningun proceso registrado con el id (" + id + ")\n");
		}
		else {
			PeticionServicio peticion = new PeticionServicio(UNDEFINED, id);
			
			RespuestaServicio respuesta = servicio.path("fallo")
					.request(MediaType.APPLICATION_JSON)
					.post(Entity.entity(peticion, MediaType.APPLICATION_JSON), RespuestaServicio.class);
			
			if (DEBUG) {
				if (respuesta.statusCode == 200) {
					imprimePorPantalla("[DEBUG] --- Proceso (" + id + ") ha cambiado su estado de error.\n" +
							   		   "[DEBUG] --- Servicio (" + servicio.getUri() + ") responde: " + respuesta.message + "\n");
				}
				else {
					imprimePorPantalla("[DEBUG] --- Proceso (" + id + ") no ha cambiado su estado de error.\n" +
								   	   "[DEBUG] --- Servicio (" + servicio.getUri() + ") responde: " + respuesta.message + "\n");
				}
			}
		}
	}
	
	@SuppressWarnings("unused")
	public static void iniciarConsenso(int v) {
		AtomicInteger contador = new AtomicInteger(0);
		CountDownLatch latch = new CountDownLatch((procesos.size() / 2 + 1));
		
		for (Map.Entry<Integer, WebTarget> p: procesos.entrySet()) {
			int id = p.getKey();
			WebTarget servicio = p.getValue();
			PeticionServicio peticion = new PeticionServicio(v, id);
			
			servicio.path("propuesta").request(MediaType.APPLICATION_JSON).async()
				.post(Entity.entity(peticion, MediaType.APPLICATION_JSON), new InvocationCallback<RespuestaServicio>() {
					
					@Override
					public void completed(RespuestaServicio respuesta) {
						if (respuesta.statusCode == 200) {
							if (DEBUG) {
								imprimePorPantalla("[DEBUG] --- Proceso (" + id + ") ha mandado mensaje de confirmacion.\n" +
								   		   		   "[DEBUG] --- Servicio (" + servicio.getUri() + ") responde: " + respuesta.message + "\n");	
							}
							// DECREMENTAR EL CONTADOR
							contador.incrementAndGet();
							
			    			// REDUCIR CONTADOR
							latch.countDown();
						}
						else if (DEBUG) {
							imprimePorPantalla("[DEBUG] --- Proceso (" + id + ") no ha mandado su mensaje de confirmacion.\n" +
										   	   "[DEBUG] --- Servicio (" + servicio.getUri() + ") responde: " + respuesta.message + "\n");
						}					
					}
					
					@Override
					public void failed(Throwable throwable) {
						if (DEBUG) {
							imprimePorPantalla("[DEBUG] --- Servicio (" + servicio.getUri() + ") ha lanzado una excepción retornando mensaje de confirmacion del proceso (" + id + ")\n" + 
											   "[DEBUG] --- " + throwable.getCause() + ": " + throwable.getMessage() + "\n");
						}
					}
				});
		}
		
		try {
			boolean result = latch.await(TIME_OUT, TIME_UNIT);
			
			if (!result && DEBUG) {
				int cont = contador.get();
				imprimePorPantalla("[DEBUG] --- Cliente interrumpido por time out esperando por recibir las confirmaciones de todos los procesos.\n");
				imprimePorPantalla("[DEBUG] --- Valor del contador de confirmaciones para quorum: " + cont + "\n");
			}
			
			if (result) {
				imprimePorPantalla("Hay una mayoria de procesos ha cometido el cambio, el nuevo valor para la variable es " + v + ".\n");	
			}
			else {
				imprimePorPantalla("No hay una mayoria de procesos que haya cometido el cambio.\n");
			}
		} catch (InterruptedException e) {
			if (DEBUG) {
				imprimePorPantalla("[DEBUG] --- Cliente interrumpido por excepcion esperando por recibir los mensajes de confirmacion de todos los procesos.\n" +
								   "[DEBUG] --- " + e.getCause() + ": " + e.getMessage() + "\n");
			}
		}
	}
	
	public static void mostrarEstado() {
		List<EstadoProceso> estados = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch latch = new CountDownLatch(procesos.size());
		
	    for (Map.Entry<Integer, WebTarget> p: procesos.entrySet()) {
	    	int id = p.getKey();
	    	WebTarget servicio = p.getValue();
	    	PeticionServicio peticion = new PeticionServicio(UNDEFINED, id);
	    	
	    	servicio.path("estado").request(MediaType.APPLICATION_JSON).async()
		    	.post(Entity.entity(peticion, MediaType.APPLICATION_JSON), new InvocationCallback<EstadoProceso>(){
					
		    		@Override
					public void completed(EstadoProceso estado) {
						if (DEBUG) {
							if (estado.statusCode == 200) {
								imprimePorPantalla("[DEBUG] --- Proceso (" + id + ") ha retornado su estado.\n" +
										   		   "[DEBUG] --- Servicio (" + servicio.getUri() + ") responde: " + estado.message + "\n");
							}
							else {
								imprimePorPantalla("[DEBUG] --- Proceso (" + id + ") no ha retornado su estado.\n" +
											   	   "[DEBUG] --- Servicio (" + servicio.getUri() + ") responde: " + estado.message + "\n");
							}
						}
		    			// AÑADIR ESTADO A LA COLECCION
		    			estados.add(estado);
		    			
						// REDUCIR CONTADOR
						latch.countDown();
					}
	
					@Override
					public void failed(Throwable throwable) {
						if (DEBUG) {
							imprimePorPantalla("[DEBUG] --- Servicio (" + servicio.getUri() + ") ha lanzado una excepción retornando el estado del proceso (" + id + ").\n" +
											   "[DEBUG] --- " + throwable.getCause() + ": " + throwable.getMessage() + "\n");
						}
						// REDUCIR CONTADOR
						latch.countDown();
					}
		    
		    	});
	    }
	    
	    try {
			boolean result = latch.await(TIME_OUT, TIME_UNIT);
			
			if (!result) {
				if (DEBUG) {
					imprimePorPantalla("[DEBUG] --- Cliente interrumpido por time out esperando a recibir el estado de todos los procesos.\n");
				}
				return;
			}
		} catch (InterruptedException e) {
			if (DEBUG) {
				imprimePorPantalla("[DEBUG] --- Cliente interrumpido por excepcion esperando a recibir el estado de todos los procesos.\n" +
								   "[DEBUG] --- " + e.getCause() + ": " + e.getMessage() + "\n");
			}
			return;
		}

	    // ORDENAR PROCESOS POR ID
	    estados.sort(Comparator.comparingInt(e -> e.id));

	    // IMPRIMIR CABECERA DE LA TABLA
	    imprimePorPantalla("\n");
	    imprimePorPantalla(String.format("%-6s %-10s %-8s %-20s %-20s%n", "ID", "VARIABLE", "ERROR", "COMPROMISOS", "COMISIONES"));
	    imprimePorPantalla("─-------------------------------------------------------------------\n");

	    for (EstadoProceso estado : estados) {
	        String variable = (estado.variable == UNDEFINED) ? "UNDEF" : String.valueOf(estado.variable);
	        imprimePorPantalla(String.format("%-6d %-10s %-8s %-20s %-20s\n",
	        	estado.id,
	            variable,
	            estado.error,
	            estado.compromisos.toString(),
	            estado.comisiones.toString()
	        ));
	    }

	    imprimePorPantalla("\n");
	}
	
	public static void mostrarAyuda() {
		imprimePorPantalla("\n========================================================\n");
		imprimePorPantalla("                  AYUDA - CLIENTE PBFT\n");
		imprimePorPantalla("========================================================\n\n");

		imprimePorPantalla("Comandos disponibles:\n\n");

		imprimePorPantalla("f<N>  -> Fallo\n");
		imprimePorPantalla("         Cambia el estado de error del proceso N.\n");
		imprimePorPantalla("         Si estaba en 'true' pasa a 'false' y viceversa.\n\n");

		imprimePorPantalla("s<X>  -> Cambiar valor\n");
		imprimePorPantalla("         Propone un cambio del valor de la variable a X\n");
		imprimePorPantalla("         e inicia un consenso entre todos los procesos.\n\n");

		imprimePorPantalla("e     -> Estado\n");
		imprimePorPantalla("         Muestra una tabla con la siguiente informacion:\n");
		imprimePorPantalla("           - id del proceso\n");
		imprimePorPantalla("           - valor actual\n");
		imprimePorPantalla("           - estado de error\n");
		imprimePorPantalla("           - compromisos recibidos\n");
		imprimePorPantalla("           - comisiones recibidas\n\n"); 

		imprimePorPantalla("h     -> Ayuda\n");
		imprimePorPantalla("         Muestra este menú de ayuda\n\n");

		imprimePorPantalla("q     -> Salir\n");
		imprimePorPantalla("         Manda una orden de cierre a todos los servicios\n");
		imprimePorPantalla("         y termina a este cliente\n\n");

		imprimePorPantalla("========================================================\n\n");
	}
	
	public static void imprimePorPantalla(String msg) {
		synchronized(PANTALLA) {
			System.out.print(msg);
			System.out.flush();
		}
	}
}