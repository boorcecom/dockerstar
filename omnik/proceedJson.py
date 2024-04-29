#!/bin/python3
import json
import pymysql.cursors
import datetime
import requests

token = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOlsiMDE0NTUyODIxNjAyMDUiXSwiZXhwIjoxODA4Mzk1NzI1LCJpYXQiOjE3MTQwNDY5MjV9.rHmVwAk1lRw5okanTJbw5cXbsxUMBfqloCBmr4hDZgc'
PRM='01455282160205'
apiurl= 'https://conso.boris.sh/api/'
cert_path='/etc/ssl/certs/ca-certificates.crt'

strDateTime=datetime.datetime.now().strftime("%Y-%m-%d")

mydb= pymysql.connect(
    host="192.168.0.253",
    user="root",
    password="Lapin@Ouais2022",
    database="solar",
    cursorclass=pymysql.cursors.DictCursor
)


def callLynkyApi(apiname, startDate, endDate):
    header={ "Authorization" : "Bearer "+token }
    response=requests.get(apiurl+apiname+"?prm="+PRM+"&start="+startDate+"&end="+endDate,headers=header,verify=cert_path)
    return response.json()['interval_reading']

with mydb:
    with mydb.cursor() as cursor:
        sql = "select to_char(max(date),\"YYYY-MM-DD\") from lnk_conso_daily;"
        cursor.execute(sql)
        result=cursor.fetchone()
        maxConsoDailyDate=result['to_char(max(date),"YYYY-MM-DD")']
        sql = "select to_char(max(datetime),\"YYYY-MM-DD\") from lnk_conso_hourly;"
        cursor=mydb.cursor()
        cursor.execute(sql)
        result=cursor.fetchone()
        maxConsoHourlyDate=result['to_char(max(datetime),"YYYY-MM-DD")']
        sql = "select to_char(max(date),\"YYYY-MM-DD\") from lnk_prod_daily;"
        cursor=mydb.cursor()
        cursor.execute(sql)
        result=cursor.fetchone()
        maxProdDailyDate=result['to_char(max(date),"YYYY-MM-DD")']
        sql = "select to_char(max(datetime),\"YYYY-MM-DD\") from lnk_prod_hourly;"
        cursor=mydb.cursor()
        cursor.execute(sql)
        result=cursor.fetchone()
        maxProdHourlyDate=result['to_char(max(datetime),"YYYY-MM-DD")']
        cursor=mydb.cursor()
        request="insert into lnk_conso_daily (date, conso) VALUES(STR_TO_DATE(%s,\"%%Y-%%m-%%d\"),%s) on duplicate key update date=STR_TO_DATE(%s,\"%%Y-%%m-%%d\"), conso=%s;"
        for data in callLynkyApi("daily_consumption",maxProdDailyDate,strDateTime):
            cursor.execute(request,(data['date'],data['value'],data['date'],data['value']))
        mydb.commit()
        cursor=mydb.cursor()
        request="insert into lnk_prod_daily (date, prod) VALUES(STR_TO_DATE(%s,\"%%Y-%%m-%%d\"),%s) on duplicate key update date=STR_TO_DATE(%s,\"%%Y-%%m-%%d\"), prod=%s;"
        for data in callLynkyApi("daily_production",maxProdDailyDate,strDateTime):
            cursor.execute(request,(data['date'],data['value'],data['date'],data['value']))
        mydb.commit()
        cursor=mydb.cursor()
        request="insert into lnk_conso_hourly (datetime, conso) VALUES(STR_TO_DATE(%s,\"%%Y-%%m-%%d %%H:%%i:%%s\"),%s) on duplicate key update datetime=STR_TO_DATE(%s,\"%%Y-%%m-%%d %%H:%%i:%%s\"), conso=%s;"
        for data in callLynkyApi("consumption_load_curve",maxConsoHourlyDate,strDateTime):
            cursor.execute(request,(data['date'],data['value'],data['date'],data['value']))
        mydb.commit()
        cursor=mydb.cursor()
        request="insert into lnk_prod_hourly (datetime, prod) VALUES(STR_TO_DATE(%s,\"%%Y-%%m-%%d %%H:%%i:%%s\"),%s) on duplicate key update datetime=STR_TO_DATE(%s,\"%%Y-%%m-%%d %%H:%%i:%%s\"), prod=%s;"
        for data in callLynkyApi("production_load_curve",maxProdHourlyDate,strDateTime):
            cursor.execute(request,(data['date'],data['value'],data['date'],data['value']))
        mydb.commit()
