package main

import "math"

// sunElevation renvoie la hauteur du soleil (degrés au-dessus de l'horizon, sans
// réfraction) pour l'instant t (secondes unix UTC) à la latitude/longitude données
// (longitude positive à l'est). Algorithme NOAA (précision ~0,1°).
func sunElevation(unix int64, lat, lon float64) float64 {
	rad := math.Pi / 180
	jd := float64(unix)/86400 + 2440587.5
	T := (jd - 2451545) / 36525

	L0 := math.Mod(280.46646+T*(36000.76983+0.0003032*T), 360)
	M := 357.52911 + T*(35999.05029-0.0001537*T)
	e := 0.016708634 - T*(0.000042037+0.0000001267*T)
	C := math.Sin(M*rad)*(1.914602-T*(0.004817+0.000014*T)) +
		math.Sin(2*M*rad)*(0.019993-0.000101*T) +
		math.Sin(3*M*rad)*0.000289
	trueLong := L0 + C
	omega := 125.04 - 1934.136*T
	lambda := trueLong - 0.00569 - 0.00478*math.Sin(omega*rad)
	eps0 := 23 + (26+(21.448-T*(46.815+T*(0.00059-T*0.001813)))/60)/60
	eps := eps0 + 0.00256*math.Cos(omega*rad)
	decl := math.Asin(math.Sin(eps*rad) * math.Sin(lambda*rad))

	y := math.Tan(eps*rad/2) * math.Tan(eps*rad/2)
	eqTime := 4 / rad * (y*math.Sin(2*L0*rad) - 2*e*math.Sin(M*rad) +
		4*e*y*math.Sin(M*rad)*math.Cos(2*L0*rad) -
		0.5*y*y*math.Sin(4*L0*rad) - 1.25*e*e*math.Sin(2*M*rad))

	minutesUTC := math.Mod(float64(unix), 86400) / 60
	tst := math.Mod(minutesUTC+eqTime+4*lon+1440, 1440)
	ha := (tst/4 - 180) * rad

	latR := lat * rad
	sinEl := math.Sin(latR)*math.Sin(decl) + math.Cos(latR)*math.Cos(decl)*math.Cos(ha)
	return math.Asin(math.Max(-1, math.Min(1, sinEl))) / rad
}

// sunMaxElevation parcourt [start, end) par pas de 5 minutes et renvoie le maximum.
func sunMaxElevation(start, end int64, lat, lon float64) float64 {
	best := -90.0
	for t := start; t < end; t += 300 {
		if v := sunElevation(t, lat, lon); v > best {
			best = v
		}
	}
	return best
}
