-- Compte lecture seule pour solar2 (déjà créé : solarview).
-- À n'utiliser que pour en recréer un ; remplacer le mot de passe.
CREATE USER IF NOT EXISTS 'solarview'@'%' IDENTIFIED BY 'CHANGE_ME';
GRANT SELECT ON solar.* TO 'solarview'@'%';
-- Plus strict (recommandé), l'API n'a besoin que de ces tables :
-- REVOKE SELECT ON solar.* FROM 'solarview'@'%';
-- GRANT SELECT ON solar.mesure TO 'solarview'@'%';
-- GRANT SELECT ON solar.ac TO 'solarview'@'%';
-- GRANT SELECT ON solar.coordonee TO 'solarview'@'%';
