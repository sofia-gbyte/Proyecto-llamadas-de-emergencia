package cl.apppolicial.repositorio;

import cl.apppolicial.modelo.Llamada;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface LlamadaRepository extends JpaRepository<Llamada, Long> {

    List<Llamada> findByAsignadoFalse();

    List<Llamada> findByAsignadoTrueAndFechaCierreIsNull();

    List<Llamada> findByFechaCierreIsNotNullOrderByFechaCierreDesc();

    long countByAsignadoFalse();

    long countByAsignadoTrueAndFechaCierreIsNull();

    long countByPrioridadAndFechaCierreIsNull(String prioridad);

    List<Llamada> findByFechaAsignacionIsNotNull();
}
