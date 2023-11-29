package main

import (
	"database/sql"
	"fmt"
	"log"
	"net"
	"os"
	"time"

	"github.com/go-sql-driver/mysql"
)

const (
	CONN_HOST = ""
	CONN_PORT = "8989"
	CONN_TYPE = "tcp"
)

var db *sql.DB

func dbconnect() {
	// Capture connection properties.
	cfg := mysql.Config{
		User:                 "omnik",
		Passwd:               "Me6q8gjaUha",
		Net:                  "tcp",
		Addr:                 "mariadb:3306",
		DBName:               "solar",
		AllowNativePasswords: true,
	}
	// Get a database handle.
	var err error
	db, err = sql.Open("mysql", cfg.FormatDSN())
	if err != nil {
		log.Fatal(err)
	}

	pingErr := db.Ping()
	if pingErr != nil {
		log.Fatal(pingErr)
	}
	fmt.Println("Connected!")
}

func main() {
	l, err := net.Listen(CONN_TYPE, CONN_HOST+":"+CONN_PORT)
	if err != nil {
		fmt.Println("Error listening:", err.Error())
		os.Exit(1)
	}
	// Close the listener when the application closes.
	defer l.Close()
	dbconnect()
	fmt.Println("Listening on " + CONN_HOST + ":" + CONN_PORT)
	for {
		// Listen for an incoming connection.
		conn, err := l.Accept()
		if err != nil {
			fmt.Println("Error accepting: ", err.Error())
			os.Exit(1)
		}
		// Handle connections in a new goroutine.
		go handleRequest(conn)
	}
}

// Handles incoming requests.
func handleRequest(conn net.Conn) {
	// Make a buffer to hold incoming data.
	buf := make([]byte, 512)
	// Read the incoming connection into the buffer.
	length, err := conn.Read(buf)
	if err != nil {
		fmt.Println("Error reading:", err.Error())
	}

	handleData(length, buf)
	// Close the connection when you're done with it.
	conn.Close()
}

func handleData(len int, data []byte) {
	// Check length
	if len < 143 {
		fmt.Println("Length Failed")
		return
	}
	// Check Header
	if data[0] == 0x68 && data[1] == 0xA9 && data[2] == 0x41 && data[3] == 0xB0 {
		// Header OK, proceed
		fmt.Println("Header OK")
		var p_id [17]byte
		var p_temp int16
		var p_etoday int64
		var p_etotal int64
		var p_fac1 int16
		var p_fac2 int16
		var p_fac3 int16
		var p_htotal int64
		var p_iac1 int16
		var p_iac2 int16
		var p_iac3 int16
		var p_ipv1 int16
		var p_ipv2 int16
		var p_ipv3 int16
		var p_pac1 int16
		var p_pac2 int16
		var p_pac3 int16
		var p_vac1 int16
		var p_vac2 int16
		var p_vac3 int16
		var p_vpv1 int16
		var p_vpv2 int16
		var p_vpv3 int16

		for i := 0; i < len; i++ {
			var entry byte
			var entry16 int16
			var entry64 int64
			entry = data[i]
			entry16 = int16(entry)
			entry64 = int64(entry)
			//Byte 15, start of SerialNumber
			if i >= 15 && i <= 30 {
				p_id[i-15] = entry
			}

			//Byte 31-32 contain temperature
			if i == 31 {
				p_temp = 0
				p_temp = entry16 << 8
			}
			if i == 32 {
				p_temp = p_temp | entry16
			}

			//Byte 33-34 PV 1 input voltage
			if i == 33 {
				p_vpv1 = 0
				p_vpv1 = entry16 << 8
			}
			if i == 34 {
				p_vpv1 = p_vpv1 | entry16
			}

			//Byte 35-36 PV 2 input voltage
			if i == 35 {
				p_vpv2 = 0
				p_vpv2 = entry16 << 8
			}
			if i == 36 {
				p_vpv2 = p_vpv2 | entry16
			}

			//Byte 37-38 PV 3 input voltage
			if i == 37 {
				p_vpv3 = 0
				p_vpv3 = entry16 << 8
			}
			if i == 38 {
				p_vpv3 = p_vpv3 | entry16
			}

			//Byte 39-40 PV 1 input current
			if i == 39 {
				p_ipv1 = 0
				p_ipv1 = entry16 << 8
			}
			if i == 40 {
				p_ipv1 = p_ipv1 | entry16
			}

			//Byte 41-42 PV 2 input current
			if i == 41 {
				p_ipv2 = 0
				p_ipv2 = entry16 << 8
			}
			if i == 42 {
				p_ipv2 = p_ipv2 | entry16
			}

			//Byte 43-44 PV 3 input current
			if i == 43 {
				p_ipv3 = 0
				p_ipv3 = entry16 << 8
			}
			if i == 44 {
				p_ipv3 = p_ipv3 | entry16
			}

			//Byte 45-46 AC output current phase 1
			if i == 45 {
				p_iac1 = 0
				p_iac1 = entry16 << 8
			}
			if i == 46 {
				p_iac1 = p_iac1 | entry16
			}
			//Byte 47-48 AC output current phase 2
			if i == 47 {
				p_iac2 = 0
				p_iac2 = entry16 << 8
			}
			if i == 48 {
				p_iac2 = p_iac2 | entry16
			}
			//Byte 49-50 AC output current phase 3
			if i == 49 {
				p_iac3 = 0
				p_iac3 = entry16 << 8
			}
			if i == 50 {
				p_iac3 = p_iac3 | entry16
			}

			//Byte 51-52 AC output voltage phase 1
			if i == 51 {
				p_vac1 = 0
				p_vac1 = entry16 << 8
			}
			if i == 52 {
				p_vac1 = p_vac1 | entry16
			}
			//Byte 53-54 AC output voltage phase 2
			if i == 53 {
				p_vac2 = 0
				p_vac2 = entry16 << 8
			}
			if i == 54 {
				p_vac2 = p_vac2 | entry16
			}
			//Byte 55-56 AC output voltage phase 3
			if i == 55 {
				p_vac3 = 0
				p_vac3 = entry16 << 8
			}
			if i == 56 {
				p_vac3 = p_vac3 | entry16
			}

			//Byte 57-58 AC output ??what?? phase 1
			if i == 57 {
				p_fac1 = 0
				p_fac1 = entry16 << 8
			}
			if i == 58 {
				p_fac1 = p_fac1 | entry16
			}
			//Byte 59-60 AC output power phase 1
			if i == 59 {
				p_pac1 = 0
				p_pac1 = entry16 << 8
			}
			if i == 60 {
				p_pac1 = p_pac1 | entry16
			}
			//Byte 61-62 AC output ??what?? phase 2
			if i == 61 {
				p_fac2 = 0
				p_fac2 = entry16 << 8
			}
			if i == 62 {
				p_fac2 = p_fac2 | entry16
			}
			//Byte 63-64 AC output power phase 2
			if i == 63 {
				p_pac2 = 0
				p_pac2 = entry16 << 8
			}
			if i == 64 {
				p_pac2 = p_pac2 | entry16
			}
			//Byte 63-64 AC output ??what?? phase 3
			if i == 65 {
				p_fac3 = 0
				p_fac3 = entry16 << 8
			}
			if i == 66 {
				p_fac3 = p_fac3 | entry16
			}
			//Byte 67-68 AC output power phase 3
			if i == 67 {
				p_pac3 = 0
				p_pac3 = entry16 << 8
			}
			if i == 68 {
				p_pac3 = p_pac3 | entry16
			}

			//Byte 69-70 contain total kwh of today
			if i == 69 {
				p_etoday = 0
				p_etoday = entry64 << 8
			}
			if i == 70 {
				p_etoday = p_etoday | entry64
			}

			//Byte 71-74 contain total kwh
			if i == 71 {
				p_etotal = 0
				p_etotal = p_etotal | (entry64 << 24)
			}
			if i == 72 {
				p_etotal = p_etotal | (entry64 << 16)
			}
			if i == 73 {
				p_etotal = p_etotal | (entry64 << 8)
			}
			if i == 74 {
				p_etotal = p_etotal | entry64
			}

			//Byte 75-78 contain hours online
			if i == 75 {
				p_htotal = 0
				p_htotal = p_htotal | (entry64 << 24)
			}
			if i == 76 {
				p_htotal = p_htotal | (entry64 << 16)
			}
			if i == 77 {
				p_htotal = p_htotal | (entry64 << 8)
			}
			if i == 78 {
				p_htotal = p_htotal | entry64
			}
		}
		inverter_id, err := getInverterId(string(p_id[:]))
		if err != nil {
			os.Exit(1)
		}

		mesure_id, err := getNextMesureId()
		if err != nil {
			os.Exit(1)
		}
		fmt.Printf("Database : Inverter id(%d) and mesure id (%d)", inverter_id, mesure_id)
		db.Exec("insert into mesure (mesure_id, inverter_id, datetime, temperature, earned_today, earned_total, h_total) values (?,?,?,?,?,?,?)", mesure_id, inverter_id, time.Now().Format("2006-01-02 15:04:05"), float32(p_temp)/10, float32(p_etoday)/100, float32(p_etotal)/10, p_htotal)
		if err != nil {
			fmt.Printf("error on insert mesure %v", err)
		}
		db.Exec("insert into pv (mesure_id,pvnum, volts, amperes) values (?,?,?,?)", mesure_id, 1, float32(p_vpv1)/10, float32(p_ipv1)/10)
		if err != nil {
			fmt.Printf("error on insert pv1 %v", err)
		}
		db.Exec("insert into pv (mesure_id,pvnum, volts, amperes) values (?,?,?,?)", mesure_id, 2, float32(p_vpv2)/10, float32(p_ipv2)/10)
		if err != nil {
			fmt.Printf("error on insert pv2 %v", err)
		}
		db.Exec("insert into pv (mesure_id,pvnum, volts, amperes) values (?,?,?,?)", mesure_id, 3, float32(p_vpv3)/10, float32(p_ipv3)/10)
		if err != nil {
			fmt.Printf("error on insert pv3 %v", err)
		}
		db.Exec("insert into ac (mesure_id,acnum, volts, amperes, hertz, watts) values (?,?,?,?,?,?)", mesure_id, 1, float32(p_vac1)/10, float32(p_iac1)/10, float32(p_fac1)/100, p_pac1)
		if err != nil {
			fmt.Printf("error on insert ac1 %v", err)
		}
		db.Exec("insert into ac (mesure_id,acnum, volts, amperes, hertz, watts) values (?,?,?,?,?,?)", mesure_id, 2, float32(p_vac2)/10, float32(p_iac2)/10, float32(p_fac2)/100, p_pac2)
		if err != nil {
			fmt.Printf("error on insert ac2 %v", err)
		}
		db.Exec("insert into ac (mesure_id,acnum, volts, amperes, hertz, watts) values (?,?,?,?,?,?)", mesure_id, 3, float32(p_vac3)/10, float32(p_iac3)/10, float32(p_fac3)/100, p_pac3)
		if err != nil {
			fmt.Printf("error on insert ac3 %v", err)
		}
	} else {
		fmt.Println("Header failed")
		return
	}

}

func getInverterId(serial string) (int, error) {
	rows := db.QueryRow("select inverter_id from inverter where inverter_serial=?", serial)
	var inverter_id int
	if err := rows.Scan(&inverter_id); err != nil {
		if err == sql.ErrNoRows {
			// Pas d'inverter avec ce numéro de série connu !
			rows := db.QueryRow("select max(inverter_id) from inverter")
			var maxid int
			if err := rows.Scan(&maxid); err != nil {
				if err == sql.ErrNoRows {
					// Pas d'inverter en base ! l'Id est donc 1 et il faut le créer.
					_, err := db.Exec("insert into inverter (inverter_id, inverter_serial) VALUES (?,?)", 1, serial)
					if err != nil {
						return 0, fmt.Errorf("Error inserting first inverter %q:%v", serial, err)
					}
					return 1, nil
				}
			}
			_, err = db.Exec("insert into inverter (inverter_id, inverter_serial) VALUES (?,?)", maxid+1, serial)
			if err != nil {
				return 0, fmt.Errorf("Error inserting inverter %q:%v", serial, err)
			}
			return maxid + 1, nil
		}
	}
	return inverter_id, nil
}

func getNextMesureId() (int, error) {
	rows := db.QueryRow("select max(mesure_id) from mesure")
	var maxid int
	if err := rows.Scan(&maxid); err != nil {
		if err == sql.ErrNoRows {
			// Pas de mesure en base ! l'Id est donc 1.
			return 1, nil
		}
	}
	return maxid + 1, nil
}
