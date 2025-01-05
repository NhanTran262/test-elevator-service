package org.thechaddigital.elevatorwebservice.service.impl;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.thechaddigital.elevatorwebservice.constant.RequestType;
import org.thechaddigital.elevatorwebservice.dto.ElevatorDto;
import org.thechaddigital.elevatorwebservice.payload.request.ElevatorRequest;
import org.thechaddigital.elevatorwebservice.entity.Elevator;
import org.thechaddigital.elevatorwebservice.payload.response.ElevatorResponse;
import org.thechaddigital.elevatorwebservice.repository.ElevatorRepository;
import org.thechaddigital.elevatorwebservice.service.ElevatorService;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;

@Service
@RequiredArgsConstructor
public class ElevatorServiceImpl implements ElevatorService {
    private final ElevatorRepository elevatorRepository;
    private final BlockingDeque<ElevatorRequest> requestQueue = new LinkedBlockingDeque<>();
    private final ScheduledExecutorService[] scheduledExecutorServices = {
            Executors.newSingleThreadScheduledExecutor(),
            Executors.newSingleThreadScheduledExecutor(),
            Executors.newSingleThreadScheduledExecutor()
    };
    private static final Logger LOGGER = LoggerFactory.getLogger(ElevatorServiceImpl.class);

    @Override
    public List<ElevatorDto> getAllElevators() {
        LOGGER.info("ElevatorServiceImpl -> Get All Elevators Invoked.");
        try {
            List<Elevator> elevators = elevatorRepository.findAll();
            return elevators.stream()
                    .map(elevator -> {
                        ElevatorDto elevatorDto = new ElevatorDto();
                        BeanUtils.copyProperties(elevator, elevatorDto);
                        return elevatorDto;
                    }).toList();
        } catch (Exception e) {
            LOGGER.error("ElevatorServiceImpl -> Get All Elevators Error: {}.", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    public ElevatorResponse callElevator(ElevatorRequest elevatorRequest) {
        LOGGER.info("ElevatorServiceImpl -> Call Elevator Invoked.");
        try {
            requestQueue.offer(elevatorRequest);
            startProcessQueue();
            List<Elevator> elevators = elevatorRepository.findAll();
            Elevator elevator = findNearestElevator(elevators,elevatorRequest);
            return ElevatorResponse.builder()
                    .id(elevator.getId())
                    .targetFloor(elevatorRequest.getTargetFloor())
                    .build();
        } catch (Exception e) {
            LOGGER.error("ElevatorServiceImpl -> Call Elevator Error: {}.", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    public ElevatorResponse selectFloor(ElevatorRequest elevatorRequest) {
        LOGGER.info("ElevatorServiceImpl -> Select Floor Invoked.");
        try {
            Elevator elevator = elevatorRepository.findById(elevatorRequest.getElevatorId())
                    .orElseThrow(() -> new IllegalArgumentException("Elevator not found"));
            elevator.setCurrentFloor(elevatorRequest.getTargetFloor());
            elevator.setIsMovingUp(elevatorRequest.getDirection());
            elevatorRepository.save(elevator);
            return ElevatorResponse.builder()
                    .id(elevator.getId())
                    .targetFloor(elevatorRequest.getTargetFloor())
                    .build();
        } catch (Exception e) {
            LOGGER.error("ElevatorServiceImpl -> Select Floor Error: {}.", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private void processQueue(ScheduledExecutorService scheduledExecutorService) {
        LOGGER.info("ElevatorServiceImpl -> Process Queue Invoked.");
        new Thread(() -> {
            try {
                while (true) {
                    ElevatorRequest request = requestQueue.take();
                    if (request.getType().equals(RequestType.CALL) && request.getStatus().equals(false)) {
                        List<Elevator> elevators = elevatorRepository.findAll();
                        Elevator nearestElevator = findNearestElevator(elevators, request);
                        if (nearestElevator != null) {
                            synchronized (nearestElevator) {
                                request.setStatus(true);
                                elevatorRepository.save(nearestElevator);
                                scheduledExecutorService.submit(() -> moveElevator(nearestElevator));
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.error("ElevatorServiceImpl -> Process Queue Error: {}.", e.getMessage());
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
            }
        }).start();
    }

    private void startProcessQueue() {
        LOGGER.info("ElevatorServiceImpl -> Start Process Queue Invoked.");
        for (ScheduledExecutorService scheduledExecutorService : scheduledExecutorServices) {
            processQueue(scheduledExecutorService);
        }
    }

    private Elevator findNearestElevator(List<Elevator> elevators, ElevatorRequest elevatorRequest) {
        return elevators.stream()
                .filter(elevator ->
                        (elevator.getIsMovingUp() && elevatorRequest.getTargetFloor() >= elevator.getCurrentFloor())
                                || (!elevator.getIsMovingUp() && elevatorRequest.getTargetFloor() <= elevator.getCurrentFloor())
                                || !elevator.getIsDoorOpen())
                .min(Comparator.comparingInt(elevator -> Math.abs(elevator.getCurrentFloor() - elevatorRequest.getTargetFloor())))
                .orElse(null);
    }


}
