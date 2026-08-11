package cafe.orders;

class InvalidRequestException extends RuntimeException {
    InvalidRequestException(String message) {
        super(message);
    }
}

class OrderNotFoundException extends RuntimeException {
}

class OrderForbiddenException extends RuntimeException {
}

class OrderConflictException extends RuntimeException {
}
