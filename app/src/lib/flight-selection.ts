import type { FlightOption, TravelDetails } from '../../services/trips';
type Transport = NonNullable<TravelDetails['transport']>;
export function validFlightSelection(t: Transport, date: string, returning = false): boolean {
  const flight = returning ? t.returnFlight : t.flight;
  const from = returning ? t.departureLocation : t.originLocation;
  const to = returning ? t.returnArrivalLocation : t.arrivalLocation;
  return !!flight && flight.date === date && flight.originAirport === from?.name && flight.arrivalAirport === to?.name
    && flight.departureTime === (returning ? t.departureTime : t.originTime)
    && flight.arrivalTime === (returning ? t.returnArrivalTime : t.arrivalTime);
}
export function selectFlight(flight: FlightOption, returning: boolean): Partial<Transport> {
  return returning ? { returnFlight: flight, departureTime: flight.departureTime, returnArrivalTime: flight.arrivalTime }
    : { flight, originTime: flight.departureTime, arrivalTime: flight.arrivalTime };
}
