#!/usr/bin/env python3
"""
Kafka Transaction Producer
Simulates external supermarket tills sending transaction events to Kafka topics
"""

import json
import random
import time
import uuid
import logging
import os
from datetime import datetime, timezone
from typing import Dict, List, Any
from kafka import KafkaProducer
from kafka.errors import KafkaError

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

class KafkaTransactionProducer:
    def __init__(self, kafka_bootstrap_servers: str = "localhost:9092"):
        self.kafka_bootstrap_servers = kafka_bootstrap_servers
        self.topic_name = "transactions"
        
        # Initialize Kafka producer
        self.producer = KafkaProducer(
            bootstrap_servers=[kafka_bootstrap_servers],
            value_serializer=lambda v: json.dumps(v).encode('utf-8'),
            key_serializer=lambda k: k.encode('utf-8') if k else None,
            acks='all',  # Wait for all replicas to acknowledge
            retries=3,
            max_in_flight_requests_per_connection=1
        )
        
        # Sample data for generating realistic transactions
        self.stores = ["STORE-001", "STORE-002", "STORE-003", "STORE-004", "STORE-005"]
        self.tills = ["TILL-1", "TILL-2", "TILL-3", "TILL-4", "TILL-5", "TILL-6", "TILL-7", "TILL-8"]
        self.payment_methods = ["card", "cash", "contactless"]
        
        # Product catalog with realistic supermarket items
        self.products = [
            {"name": "Milk", "code": "MILK001", "price": 2.50, "category": "Dairy"},
            {"name": "Bread", "code": "BREAD001", "price": 1.20, "category": "Bakery"},
            {"name": "Coffee", "code": "COFFEE001", "price": 3.99, "category": "Beverages"},
            {"name": "Chicken Breast", "code": "CHICKEN001", "price": 8.99, "category": "Meat"},
            {"name": "Rice", "code": "RICE001", "price": 2.99, "category": "Grains"},
            {"name": "Bananas", "code": "BANANA001", "price": 1.50, "category": "Fruit"},
            {"name": "Eggs", "code": "EGGS001", "price": 2.99, "category": "Dairy"},
            {"name": "Pasta", "code": "PASTA001", "price": 1.79, "category": "Grains"},
            {"name": "Tomatoes", "code": "TOMATO001", "price": 2.49, "category": "Vegetables"},
            {"name": "Cheese", "code": "CHEESE001", "price": 4.50, "category": "Dairy"},
            {"name": "Yogurt", "code": "YOGURT001", "price": 1.99, "category": "Dairy"},
            {"name": "Apples", "code": "APPLE001", "price": 2.99, "category": "Fruit"},
            {"name": "Potatoes", "code": "POTATO001", "price": 3.49, "category": "Vegetables"},
            {"name": "Onions", "code": "ONION001", "price": 1.29, "category": "Vegetables"},
            {"name": "Cereal", "code": "CEREAL001", "price": 3.99, "category": "Breakfast"},
            {"name": "Orange Juice", "code": "OJ001", "price": 2.79, "category": "Beverages"},
            {"name": "Butter", "code": "BUTTER001", "price": 2.99, "category": "Dairy"},
            {"name": "Ham", "code": "HAM001", "price": 4.99, "category": "Deli"},
            {"name": "Lettuce", "code": "LETTUCE001", "price": 1.99, "category": "Vegetables"},
            {"name": "Cucumber", "code": "CUCUMBER001", "price": 0.99, "category": "Vegetables"}
        ]
    
    def generate_customer_id(self) -> str:
        """Generate a random customer ID"""
        return f"CUST-{random.randint(10000, 99999)}"
    
    def generate_transaction_id(self) -> str:
        """Generate a unique transaction ID"""
        return f"TXN-{uuid.uuid4().hex[:8].upper()}"
    
    def generate_timestamp(self) -> str:
        """Generate a timestamp in ISO format"""
        now = datetime.now(timezone.utc)
        return now.isoformat()
    
    def generate_random_items(self, min_items: int = 1, max_items: int = 8) -> List[Dict[str, Any]]:
        """Generate random items for a transaction"""
        num_items = random.randint(min_items, max_items)
        selected_products = random.sample(self.products, num_items)
        
        items = []
        for product in selected_products:
            quantity = random.randint(1, 3)
            items.append({
                "productName": product["name"],
                "productCode": product["code"],
                "unitPrice": round(product["price"], 2),
                "quantity": quantity,
                "category": product["category"]
            })
        
        return items
    
    def calculate_total(self, items: List[Dict[str, Any]]) -> float:
        """Calculate total amount from items"""
        total = sum(item["unitPrice"] * item["quantity"] for item in items)
        return round(total, 2)
    
    def generate_transaction_event(self) -> Dict[str, Any]:
        """Generate a complete transaction event for Kafka"""
        items = self.generate_random_items()
        total_amount = self.calculate_total(items)
        
        # Create event with additional metadata for Kafka
        event = {
            "eventId": str(uuid.uuid4()),
            "eventType": "TRANSACTION_CREATED",
            "eventTimestamp": self.generate_timestamp(),
            "source": "till-system",
            "version": "1.0",
            "data": {
                "transactionId": self.generate_transaction_id(),
                "customerId": self.generate_customer_id(),
                "storeId": random.choice(self.stores),
                "tillId": random.choice(self.tills),
                "paymentMethod": random.choice(self.payment_methods),
                "totalAmount": total_amount,
                "currency": "GBP",
                "timestamp": self.generate_timestamp(),
                "items": items
            }
        }
        
        return event
    
    def send_transaction_event(self, event: Dict[str, Any]) -> bool:
        """Send transaction event to Kafka topic"""
        try:
            # Use transaction ID as the key for partitioning
            key = event["data"]["transactionId"]
            
            # Send to Kafka
            future = self.producer.send(self.topic_name, key=key, value=event)
            
            # Wait for the send to complete
            record_metadata = future.get(timeout=10)
            
            logger.info(f"Transaction event sent to Kafka - Topic: {record_metadata.topic}, "
                       f"Partition: {record_metadata.partition}, Offset: {record_metadata.offset}, "
                       f"Transaction: {event['data']['transactionId']}, "
                       f"Store: {event['data']['storeId']}, Till: {event['data']['tillId']}, "
                       f"Amount: £{event['data']['totalAmount']}")
            
            return True
            
        except KafkaError as e:
            logger.error(f"Failed to send transaction event {event['data']['transactionId']}: {e}")
            return False
        except Exception as e:
            logger.error(f"Unexpected error sending transaction event {event['data']['transactionId']}: {e}")
            return False
    
    def run(self, messages_per_second: int = 5):
        """Run the producer continuously"""
        logger.info(f"Starting Kafka Transaction Producer - sending {messages_per_second} messages per second")
        logger.info(f"Kafka Bootstrap Servers: {self.kafka_bootstrap_servers}")
        logger.info(f"Topic: {self.topic_name}")
        
        success_count = 0
        error_count = 0
        interval = 1.0 / messages_per_second
        
        try:
            while True:
                event = self.generate_transaction_event()
                
                if self.send_transaction_event(event):
                    success_count += 1
                else:
                    error_count += 1
                
                # Log statistics every 50 messages
                if (success_count + error_count) % 50 == 0:
                    logger.info(f"Statistics - Success: {success_count}, Errors: {error_count}, "
                               f"Total: {success_count + error_count}")
                
                time.sleep(interval)
                
        except KeyboardInterrupt:
            logger.info("Kafka Transaction Producer stopped by user")
            logger.info(f"Final Statistics - Success: {success_count}, Errors: {error_count}, "
                       f"Total: {success_count + error_count}")
        finally:
            # Flush and close the producer
            self.producer.flush()
            self.producer.close()

def main():
    """Main entry point"""
    # Get configuration from environment variables
    kafka_servers = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "kafka:29092")
    messages_per_sec = int(os.getenv("MESSAGES_PER_SECOND", "5"))
    
    producer = KafkaTransactionProducer(kafka_servers)
    producer.run(messages_per_sec)

if __name__ == "__main__":
    main() 